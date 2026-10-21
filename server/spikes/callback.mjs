// Tiny local page for PayPal's return_url / cancel_url during spikes. Logs which one was hit.
import { createServer } from "node:http";

createServer((req, res) => {
  const url = new URL(req.url, "http://localhost:8787");
  const ok = url.pathname === "/return";
  console.log(new Date().toISOString(), url.pathname, "token=" + url.searchParams.get("token"));
  res.writeHead(200, { "Content-Type": "text/html" });
  res.end(`<h1 style="font-family:sans-serif">${ok ? "Approved — go back to Claude and say “approved”." : "Cancelled."}</h1>`);
}).listen(8787, () => console.log("callback listening on http://localhost:8787"));
