import { defineConfig, devices } from '@playwright/test';
export default defineConfig({
  testDir: './tests-browser',
  timeout: 120000,
  expect: { timeout: 30000 },
  fullyParallel: false,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    ...devices['Desktop Chrome'],
    launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required', '--disable-dev-shm-usage'] },
    permissions: ['microphone', 'camera']
  }
});
