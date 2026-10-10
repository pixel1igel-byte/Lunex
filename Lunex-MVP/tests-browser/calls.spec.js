import { test, expect } from '@playwright/test';

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
  await expect(page.locator('.call-card')).toContainText('Входящий');
  await page.getByRole('button', { name: 'Принять' }).click();
}

async function expectConnected(page) {
  await expect(page.locator('.call-card p').filter({ hasText: 'Соединено' })).toBeVisible({ timeout: 25000 });
}

test('two real browser peers complete audio and video WebRTC calls', async ({ browser }) => {
  const aliceContext = await browser.newContext({ permissions: ['microphone', 'camera'] });
  const bobContext = await browser.newContext({ permissions: ['microphone', 'camera'] });
  const alice = await aliceContext.newPage();
  const bob = await bobContext.newPage();
  const errors = [];
  alice.on('pageerror', error => errors.push('Alice: ' + error.message));
  bob.on('pageerror', error => errors.push('Bob: ' + error.message));
  try {
    await Promise.all([register(alice, 'e2e_alice', 'Alice E2E'), register(bob, 'e2e_bob', 'Bob E2E')]);
    await openDirectChat(alice, 'e2e_bob');
    await openDirectChat(bob, 'e2e_alice');

    await alice.getByTitle('Голосовой звонок').click();
    await acceptCall(bob);
    await Promise.all([expectConnected(alice), expectConnected(bob)]);
    await expect(alice.locator('audio')).toHaveCount(1);
    await alice.getByRole('button', { name: 'Завершить' }).click();
    await expect(alice.locator('.call-card')).toHaveCount(0);
    await expect(bob.locator('.call-card')).toHaveCount(0);

    await alice.getByTitle('Видеозвонок').click();
    await acceptCall(bob);
    await Promise.all([expectConnected(alice), expectConnected(bob)]);
    await expect(alice.locator('video.remote-video')).toBeVisible();
    await expect(bob.locator('video.remote-video')).toBeVisible();
    await expect.poll(() => alice.locator('video.remote-video').evaluate(video => video.videoWidth > 0 && video.videoHeight > 0), { timeout: 20000 }).toBe(true);
    await expect.poll(() => bob.locator('video.remote-video').evaluate(video => video.videoWidth > 0 && video.videoHeight > 0), { timeout: 20000 }).toBe(true);
    expect(errors).toEqual([]);
  } finally {
    await aliceContext.close();
    await bobContext.close();
  }
});
