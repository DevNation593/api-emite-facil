import { expect, type Page } from "@playwright/test";

type CatalogItem = { id: string; name: string };

/** El editor puede convivir con la web de dev (select) o con la rama del buscador (combobox). */
export async function selectCatalog(page: Page, label: string, item: CatalogItem): Promise<void> {
  const field = page.getByLabel(label, { exact: true });
  if (await field.getAttribute("role") === "combobox") {
    await field.fill(item.name);
    await page.getByRole("listbox").getByRole("option", { name: new RegExp(item.name) }).click();
    await expect(field).toHaveValue(new RegExp(item.name));
  } else {
    await field.selectOption(item.id);
  }
}

export async function expectCatalogSelection(page: Page, label: string, item: CatalogItem): Promise<void> {
  const field = page.getByLabel(label, { exact: true });
  if (await field.getAttribute("role") === "combobox") {
    await expect(field).toHaveValue(new RegExp(item.name));
  } else {
    await expect(field).toHaveValue(item.id);
  }
}
