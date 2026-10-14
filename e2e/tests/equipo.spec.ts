import { USERS } from "../support/env.ts";
import { expect, test } from "../support/fixtures.ts";
import { apiCall, seedCompany } from "../support/seed.ts";

// Ya no hay invitaciones: cada usuario trabaja en las empresas que crea.
test("quien crea una empresa es su propietario y no se invita a nadie", async ({ page, shot }) => {
  const company = await seedCompany(page);

  await page.goto("/equipo");
  const members = page.getByRole("table", { name: "Miembros" });
  const owner = members.getByRole("row", { name: new RegExp(USERS.demo.email) });
  await expect(owner).toContainText("Propietario");
  await expect(owner).toContainText("(usted)");
  await expect(page.getByText(/Invitar/)).toHaveCount(0);
  await shot("equipo");

  // La API tampoco tiene invitaciones.
  const invite = await apiCall(page, "POST", "/invitations", {
    tenantId: company.tenantId,
    body: { email: "alguien@example.test", role: "AUDITOR" },
  });
  expect(invite.status).toBe(404);
});
