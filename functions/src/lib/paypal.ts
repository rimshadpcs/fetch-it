// PayPal Orders v2 with a vaulted PayPal wallet (Spike 2): charge with no buyer redirect.

export interface ShippingAddress {
  fullName: string;
  line1: string;
  line2?: string | null;
  city: string;
  state: string;
  postalCode: string;
  countryCode: string;
}

export interface ChargeResult {
  orderId: string;
  status: string;
  captureId: string | null;
  captureStatus: string | null;
}

/**
 * A failed PayPal call. Carries only what PayPal returns about the failure (never credentials or tokens):
 * the error name (OAuth `error` like "invalid_client", or REST `name` like "UNPROCESSABLE_ENTITY"),
 * the first `issue`, the HTTP status and the `debug_id` PayPal support asks for.
 */
export class PayPalError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly debugId: string | null,
    readonly issue: string | null,
    readonly paypalName: string | null = null,
  ) {
    super(message);
    this.name = "PayPalError";
  }

  /** Safe to log: no request bodies, no credentials. */
  toLog() {
    return { paypalError: this.paypalName, issue: this.issue, status: this.status, debugId: this.debugId, message: this.message };
  }
}

const debugIdOf = (res: Response, body?: { debug_id?: string }) => body?.debug_id ?? res.headers.get("paypal-debug-id");

let cached: { key: string; token: string; expiresAt: number } | null = null;

export class PayPal {
  constructor(
    private baseUrl: string,
    private clientId: string,
    private clientSecret: string,
  ) {}

  /** Sandbox tokens last ~9 h; reuse per instance until a minute before expiry. */
  private async token(): Promise<string> {
    const key = `${this.baseUrl}|${this.clientId}`;
    if (cached && cached.key === key && cached.expiresAt > Date.now() + 60_000) return cached.token;
    const res = await fetch(`${this.baseUrl}/v1/oauth2/token`, {
      method: "POST",
      headers: {
        Authorization: `Basic ${Buffer.from(`${this.clientId}:${this.clientSecret}`).toString("base64")}`,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: "grant_type=client_credentials",
      signal: AbortSignal.timeout(10_000),
    });
    const json = (await res.json().catch(() => ({}))) as { access_token?: string; expires_in?: number; error?: string; error_description?: string };
    if (!res.ok || !json.access_token) {
      // e.g. 401 invalid_client "Client Authentication failed" = wrong client id/secret pair for this base URL.
      throw new PayPalError(
        `PayPal auth failed: ${json.error ?? res.status}${json.error_description ? ` (${json.error_description})` : ""}`,
        res.status,
        debugIdOf(res),
        null,
        json.error ?? "AUTHENTICATION_FAILURE",
      );
    }
    cached = { key, token: json.access_token, expiresAt: Date.now() + (json.expires_in ?? 0) * 1000 };
    return json.access_token;
  }

  /**
   * One-step charge with the vault token. Orders created with a vault_id come back COMPLETED on create
   * (Spike 2), so there's no separate capture. `requestId` makes retries idempotent: use the purchase id.
   */
  async chargeVaulted(opts: {
    requestId: string;
    vaultId: string;
    amount: number;
    currency: string;
    description: string;
    shipTo: ShippingAddress;
  }): Promise<ChargeResult> {
    const { shipTo } = opts;
    const res = await fetch(`${this.baseUrl}/v2/checkout/orders`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${await this.token()}`,
        "Content-Type": "application/json",
        "PayPal-Request-Id": opts.requestId,
        Prefer: "return=representation",
      },
      body: JSON.stringify({
        intent: "CAPTURE",
        purchase_units: [
          {
            reference_id: opts.requestId,
            custom_id: opts.requestId,
            description: opts.description.slice(0, 127),
            amount: { currency_code: opts.currency, value: opts.amount.toFixed(2) },
            shipping: {
              type: "SHIPPING",
              name: { full_name: shipTo.fullName },
              address: {
                address_line_1: shipTo.line1,
                ...(shipTo.line2 && { address_line_2: shipTo.line2 }),
                admin_area_2: shipTo.city,
                admin_area_1: shipTo.state,
                postal_code: shipTo.postalCode,
                country_code: shipTo.countryCode,
              },
            },
          },
        ],
        payment_source: {
          paypal: {
            vault_id: opts.vaultId,
            // Ship to the saved place the rules approved, not whatever is on file at PayPal.
            experience_context: { shipping_preference: "SET_PROVIDED_ADDRESS" },
          },
        },
      }),
      signal: AbortSignal.timeout(20_000),
    });
    const json = (await res.json().catch(() => ({}))) as {
      id?: string;
      status?: string;
      name?: string;
      debug_id?: string;
      message?: string;
      details?: { issue?: string; description?: string }[];
      purchase_units?: { payments?: { captures?: { id: string; status: string }[] } }[];
    };
    if (!res.ok || !json.id) {
      const issue = json.details?.[0]?.issue ?? null;
      throw new PayPalError(
        `PayPal ${res.status}: ${issue ?? json.message ?? "order failed"} ${json.details?.[0]?.description ?? ""}`.trim(),
        res.status,
        debugIdOf(res, json),
        issue,
        json.name ?? null,
      );
    }
    const capture = json.purchase_units?.[0]?.payments?.captures?.[0];
    return { orderId: json.id, status: json.status ?? "UNKNOWN", captureId: capture?.id ?? null, captureStatus: capture?.status ?? null };
  }

  /** Authenticated JSON call; throws PayPalError with PayPal's issue + debug id on failure. */
  private async request<T>(method: string, path: string, body?: unknown, requestId?: string): Promise<T> {
    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${await this.token()}`,
        "Content-Type": "application/json",
        Prefer: "return=representation",
        ...(requestId && { "PayPal-Request-Id": requestId }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(20_000),
    });
    const text = await res.text();
    let parsed: unknown = {};
    try {
      parsed = text ? JSON.parse(text) : {};
    } catch {
      parsed = { message: `non-JSON response (${res.status})` };
    }
    const json = parsed as T & {
      name?: string;
      debug_id?: string;
      message?: string;
      details?: { issue?: string; description?: string }[];
    };
    if (!res.ok) {
      const issue = json.details?.[0]?.issue ?? null;
      throw new PayPalError(
        `PayPal ${res.status} ${method} ${path.split("?")[0]}: ${issue ?? json.message ?? "failed"} ${json.details?.[0]?.description ?? ""}`.trim(),
        res.status,
        debugIdOf(res, json),
        issue,
        json.name ?? null,
      );
    }
    return json;
  }

  /**
   * Vault a PayPal wallet without charging anything (Payment Method Tokens v3 setup token). The payer approves
   * at the `approve` link; then call createPaymentToken. Return/cancel can be app deep links (fetchit://…).
   */
  createSetupToken(opts: { requestId: string; returnUrl: string; cancelUrl: string }) {
    return this.request<{ id: string; status: string; links: { rel: string; href: string }[] }>(
      "POST",
      "/v3/vault/setup-tokens",
      {
        payment_source: {
          paypal: {
            description: "Fetch It pays for things you approve, within your house rules",
            usage_type: "MERCHANT",
            customer_type: "CONSUMER",
            usage_pattern: "UNSCHEDULED_PREPAID",
            permit_multiple_payment_tokens: false,
            experience_context: {
              brand_name: "Fetch It",
              return_url: opts.returnUrl,
              cancel_url: opts.cancelUrl,
              // Ask PayPal for the buyer's shipping address so we can suggest it as "Home".
              shipping_preference: "GET_FROM_FILE",
              vault_instruction: "ON_CREATE_PAYMENT_TOKENS",
            },
          },
        },
      },
      opts.requestId,
    );
  }

  getSetupToken(id: string) {
    return this.request<{ id: string; status: string }>("GET", `/v3/vault/setup-tokens/${encodeURIComponent(id)}`);
  }

  /** Exchange an approved setup token for the reusable payment token (the vault id used as `vault_id`). */
  createPaymentToken(setupTokenId: string, requestId: string) {
    return this.request<{
      id: string;
      customer?: { id: string };
      payment_source?: {
        paypal?: {
          email_address?: string;
          payer_id?: string;
          name?: { given_name?: string; surname?: string; full_name?: string };
          shipping?: { name?: { full_name?: string }; address?: PayPalAddress };
          address?: PayPalAddress;
        };
      };
    }>("POST", "/v3/vault/payment-tokens", { payment_source: { token: { id: setupTokenId, type: "SETUP_TOKEN" } } }, requestId);
  }

  /** Orders v2 add-tracking. `carrier` is one of PayPal's carrier codes; "OTHER" needs `carrierNameOther`. */
  addTracking(orderId: string, t: { captureId: string; trackingNumber: string; carrier: string; carrierNameOther?: string; notifyPayer: boolean; requestId: string }) {
    return this.request<{ id: string; status: string; purchase_units?: { shipping?: { trackers?: { id: string; status: string }[] } }[] }>(
      "POST",
      `/v2/checkout/orders/${encodeURIComponent(orderId)}/track`,
      {
        capture_id: t.captureId,
        tracking_number: t.trackingNumber,
        carrier: t.carrier,
        ...(t.carrierNameOther && { carrier_name_other: t.carrierNameOther }),
        notify_payer: t.notifyPayer,
      },
      t.requestId,
    );
  }

  /** Full refund of a capture (omit amount). `requestId` makes a retried cancel refund once. */
  refundCapture(captureId: string, opts: { requestId: string; note: string }) {
    return this.request<{ id: string; status: string; amount?: { value: string; currency_code: string } }>(
      "POST",
      `/v2/payments/captures/${encodeURIComponent(captureId)}/refund`,
      { note_to_payer: opts.note.slice(0, 255) },
      opts.requestId,
    );
  }

  /**
   * PayPal's own check of a webhook delivery (transmission headers + our webhook id + the event as sent).
   * True only for verification_status SUCCESS.
   */
  async verifyWebhook(headers: Record<string, string | undefined>, webhookId: string, event: unknown): Promise<boolean> {
    const h = (k: string) => headers[k] ?? "";
    const res = await this.request<{ verification_status?: string }>("POST", "/v1/notifications/verify-webhook-signature", {
      auth_algo: h("paypal-auth-algo"),
      cert_url: h("paypal-cert-url"),
      transmission_id: h("paypal-transmission-id"),
      transmission_sig: h("paypal-transmission-sig"),
      transmission_time: h("paypal-transmission-time"),
      webhook_id: webhookId,
      webhook_event: event,
    });
    return res.verification_status === "SUCCESS";
  }

  /** Register (or list) our webhook listener for the given event types. */
  createWebhook(url: string, eventTypes: string[]) {
    return this.request<{ id: string; url: string }>("POST", "/v1/notifications/webhooks", { url, event_types: eventTypes.map((name) => ({ name })) });
  }

  listWebhooks() {
    return this.request<{ webhooks: { id: string; url: string; event_types: { name: string }[] }[] }>("GET", "/v1/notifications/webhooks");
  }

  getOrder(orderId: string) {
    return this.request<Record<string, unknown>>("GET", `/v2/checkout/orders/${encodeURIComponent(orderId)}`);
  }
}

export interface PayPalAddress {
  address_line_1?: string;
  address_line_2?: string;
  admin_area_2?: string;
  admin_area_1?: string;
  postal_code?: string;
  country_code: string;
}
