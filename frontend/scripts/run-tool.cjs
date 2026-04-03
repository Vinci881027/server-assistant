const { spawnSync } = require('child_process');
const path = require('path');
const { ensureSupportedNode } = require('./check-node.cjs');

const BIN_NAMES = {
  eslint: 'eslint',
  playwright: 'playwright',
  vite: 'vite',
  vitest: 'vitest',
};

function main() {
  ensureSupportedNode();

  const [, , toolName, ...toolArgs] = process.argv;

  if (!toolName) {
    console.error('Usage: node ./scripts/run-tool.cjs <vite|eslint|vitest|playwright> [...args]');
    process.exit(1);
  }

  const binName = BIN_NAMES[toolName];

  if (!binName) {
    console.error(`Unsupported tool "${toolName}".`);
    process.exit(1);
  }

  const localBinPath = path.resolve(__dirname, '..', 'node_modules', '.bin', binName);
  const result = spawnSync(localBinPath, toolArgs, {
    stdio: 'inherit',
  });

  if (result.error) {
    throw result.error;
  }

  process.exit(result.status == null ? 1 : result.status);
}

main();
