import { test, expect } from '@playwright/test';
import { writeFileSync } from 'node:fs';

async function register(page, username, displayName) {
  await page.goto('/');
  await page.getByRole('button', { name: 'Создать аккаунт' }).first().click();
  await page.getByPlaceholder('например, lunex_user').fill(username);
  await page.getByPlaceholder('Как тебя будут видеть').fill(displayName);
  await page.getByPlaceholder('Пароль').fill('Lunex-test-password-2026');
  await page.locator('.login-card button.primary').click();
  await expect(page.locator('.profile-footer')).toContainText(displayName);
}

async function openDirectChat(page, username) {
  await page.getByTitle('Новый личный чат').click();
  await page.getByPlaceholder('Логин пользователя').fill(username);
  await page.getByRole('button', { name: 'Открыть чат' }).click();
  await expect(page.locator('.chat-header')).toContainText(username);
}

async function acceptCall(page) {
  await expect(page.locator('.call-card')).toContainText('Входящий', { timeout: 90000 });
  await page.getByRole('button', { name: 'Принять' }).click();
}

async function expectConnected(page) {
  await expect(page.locator('.call-card p').filter({ hasText: 'Соединено' })).toBeVisible({ timeout: 60000 });
}

test('native Android peer exchanges live audio and video with a browser peer', async ({ browser }) => {
  const context = await browser.newContext({ permissions: ['microphone', 'camera'] });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    const socketConnected = page.waitForEvent('websocket', { predicate: ws => ws.url().includes('/socket.io/'), timeout: 20000 });
    await register(page, 'e2e_bob', 'Bob E2E');
    await socketConnected;
    await page.waitForTimeout(500);
    writeFileSync('/tmp/lunex-browser-ready', 'ready');

    await acceptCall(page);
    await expectConnected(page);
    await expect.poll(() => page.locator('audio').evaluate(el => Boolean(el.srcObject?.getAudioTracks().some(track => track.readyState === 'live'))), { timeout: 20000 }).toBe(true);
    await expect(page.locator('.call-card')).toHaveCount(0, { timeout: 90000 });

    await acceptCall(page);
    await expectConnected(page);
    await expect(page.locator('video.remote-video')).toBeVisible();
    await expect.poll(() => page.locator('video.remote-video').evaluate(video => video.videoWidth > 0 && video.videoHeight > 0), { timeout: 30000 }).toBe(true);
    await expect.poll(() => page.locator('audio').evaluate(el => Boolean(el.srcObject?.getAudioTracks().some(track => track.readyState === 'live'))), { timeout: 20000 }).toBe(true);
    await expect(page.locator('.call-card')).toHaveCount(0, { timeout: 90000 });
    expect(errors).toEqual([]);
  } finally {
    await context.close();
  }
});
