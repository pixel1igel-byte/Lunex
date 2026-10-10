import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests-browser',
  timeout: 90000,
  expect: { timeout: 15000 },
  fullyParallel: false,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    ...devices['Desktop Chrome'],
    launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required', '--disable-dev-shm-usage'] },
    permissions: ['microphone', 'camera']
  },
  webServer: [
    { command: 'npm run server', url: 'http://127.0.0.1:3001/api/health', reuseExistingServer: !process.env.CI, timeout: 30000 },
    { command: 'npm run client -- --host 127.0.0.1', url: 'http://127.0.0.1:5173', reuseExistingServer: !process.env.CI, timeout: 30000 }
  ]
});
