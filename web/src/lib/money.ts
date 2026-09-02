/**
 * An amount, with the two decimal places the platform keeps it to.
 *
 * <p>Needed because JSON has one number type and JavaScript parses it into a double: the service
 * sends {@code 500.00} and {@code JSON.parse} hands React the number {@code 500}, which renders as
 * "500" next to a tax figure that happened to render as "18.00". The scale is preserved end to end
 * — {@code numeric(14,2)} in the database, {@code BigDecimal} in Java, two decimals on the wire —
 * and this is the last step of that, not a decoration.
 *
 * <p>Grouped in the Indian convention (lakh, crore), because that is who reads these screens. No
 * currency symbol: the platform is not configured with a currency, and inventing one on a bill
 * would be worse than leaving the number to speak for itself.
 */
export function money(amount: number | string | null | undefined): string {
  if (amount === null || amount === undefined || amount === "") return "—";
  const value = typeof amount === "number" ? amount : Number(amount);
  if (!Number.isFinite(value)) return String(amount);
  return new Intl.NumberFormat("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value);
}
