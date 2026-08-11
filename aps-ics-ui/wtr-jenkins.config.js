import { chromeLauncher } from '@web/test-runner'
import { vitePlugin } from '@remcovaes/web-test-runner-vite-plugin';
process.env.NODE_ENV = 'development'

export default {
  concurrency: 1,
  browsers: [
    chromeLauncher({
      launchOptions: {
        executablePath: '/local/jenkins/jobs/ESW-OCS-Engineering-UI-Nightly/workspace/chrome-linux64/chrome',
        headless: true,
        devtools: true,
      },
    }),
  ],
  concurrentBrowsers: 1,
  testsFinishTimeout: 500000,
  testFramework: {
    config: {
      timeout: '3000'
    }
  },
  plugins: [
    vitePlugin()
  ],
  testRunnerHtml: (testFramework) => `
    <html>
      <head>
        <script type="module">
          // Note: globals expected by @testing-library/react
          window.global = window;
          window.process = { env: {} };
          // Note: adapted from https://github.com/vitejs/vite/issues/1984#issuecomment-778289660
          // Note: without this you'll run into https://github.com/vitejs/vite-plugin-react/pull/11#discussion_r430879201
          window.__vite_plugin_react_preamble_installed__ = true;
        </script>
        <script type="module" src="${testFramework}"></script>
      </head>
    </html>
  `
}
