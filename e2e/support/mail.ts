// El servidor SMTP de las pruebas (Mailpit) solo captura: aquí se le pregunta qué llegó.

import { expect } from "@playwright/test";

import { URLS } from "./env.ts";

export type ReceivedMail = {
  subject: string;
  from: string;
  attachments: { fileName: string; contentType: string }[];
};

type MailpitSummary = { ID: string };
type MailpitMessage = {
  Subject: string;
  From: { Address: string };
  Attachments: { FileName: string; ContentType: string }[];
};

async function messagesTo(address: string): Promise<MailpitSummary[]> {
  const response = await fetch(`${URLS.mailpit}/api/v1/search?query=${encodeURIComponent(`to:${address}`)}`);
  if (!response.ok) {
    throw new Error(`Mailpit respondió ${response.status}`);
  }
  return ((await response.json()) as { messages: MailpitSummary[] }).messages;
}

/** Espera `count` mensajes para esa dirección y devuelve el más reciente. */
export async function waitForMail(address: string, count = 1): Promise<ReceivedMail> {
  await expect.poll(async () => (await messagesTo(address)).length, { timeout: 60_000 }).toBe(count);
  const [latest] = await messagesTo(address);
  const response = await fetch(`${URLS.mailpit}/api/v1/message/${latest!.ID}`);
  const message = (await response.json()) as MailpitMessage;
  return {
    subject: message.Subject,
    from: message.From.Address,
    attachments: message.Attachments.map((attachment) => ({
      fileName: attachment.FileName,
      contentType: attachment.ContentType,
    })),
  };
}
