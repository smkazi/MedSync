import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypescript from "eslint-config-next/typescript";
import security from "eslint-plugin-security";

/**
 * eslint-config-next 16 ships flat configs directly, so they are spread here rather than bridged
 * through FlatCompat — which the eslintrc compatibility layer cannot validate under ESLint 10.
 */
const config = [
  ...nextCoreWebVitals,
  ...nextTypescript,
  {
    plugins: { security },
    rules: {
      ...security.configs.recommended.rules,
      // Fires on ordinary indexed access and is pure noise here. The rules that matter —
      // eval, child_process, unsafe regex, non-literal fs paths — stay on.
      "security/detect-object-injection": "off",
    },
  },
  { ignores: [".next/**", "node_modules/**", "e2e/**"] },
];

export default config;
