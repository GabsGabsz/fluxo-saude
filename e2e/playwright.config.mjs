// E2E da interface contra a aplicação REAL (jar) + PostgreSQL REAL com dados fictícios
// (ver e2e/README.md e o job "e2e" do CI). Execução serial: os cenários compartilham o banco.
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './testes',
  fullyParallel: false,
  workers: 1,
  retries: 0,                       // falha intermitente deve ser investigada, não mascarada
  forbidOnly: !!process.env.CI,
  timeout: 90_000,
  expect: { timeout: Number(process.env.E2E_EXPECT_MS || 15_000) },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'relatorio' }]],
  outputDir: 'resultados',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:8080',
    locale: 'pt-BR',
    // Fuso do NAVEGADOR diferente dos fusos das unidades: prova que a tela usa o fuso da unidade.
    timezoneId: 'Europe/Lisbon',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 900 } } },
  ],
});
