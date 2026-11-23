declare module "country-list" {
  export type Country = { code: string; name: string };
  export function getData(): Country[];
}
