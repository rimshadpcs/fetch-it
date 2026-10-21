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

export class PayPalError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly debugId: string | null,
    readonly issue: string | null,
  ) {
    super(message);
  }
}

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
    const json = (await res.json()) as { access_token?: string; expires_in?: number; error_description?: string };
    if (!res.ok || !json.access_token) throw new PayPalError(`PayPal auth failed: ${json.error_description ?? res.status}`, res.status, null, null);
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
    const json = (await res.json()) as {
      id?: string;
      status?: string;
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
        json.debug_id ?? null,
        issue,
      );
    }
    const capture = json.purchase_units?.[0]?.payments?.captures?.[0];
    return { orderId: json.id, status: json.status ?? "UNKNOWN", captureId: capture?.id ?? null, captureStatus: capture?.status ?? null };
  }
}
