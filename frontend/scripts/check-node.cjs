const SUPPORTED_NODE_MESSAGE =
  'Node.js ^20.19.0 or >=22.12.0 is required for this frontend toolchain.';

function parseNodeVersion(version) {
  const match = /^v?(\d+)\.(\d+)\.(\d+)$/.exec(version || '');

  if (!match) {
    return null;
  }

  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
  };
}

function isSupportedNode(version) {
  const parsed = parseNodeVersion(version);

  if (!parsed) {
    return false;
  }

  if (parsed.major === 20) {
    return parsed.minor >= 19;
  }

  if (parsed.major === 22) {
    return parsed.minor >= 12;
  }

  return parsed.major > 22;
}

function ensureSupportedNode() {
  if (isSupportedNode(process.versions.node)) {
    return;
  }

  const currentVersion = process.version || 'unknown';

  console.error(SUPPORTED_NODE_MESSAGE);
  console.error(`Current Node.js version: ${currentVersion}`);
  console.error('Your current version may crash on modern syntax such as ??= before Vite can start.');
  console.error('Recommended fix: upgrade Node.js to 20.19.x LTS or 22.12+ and then reinstall dependencies.');
  process.exit(1);
}

if (require.main === module) {
  ensureSupportedNode();
}

module.exports = {
  SUPPORTED_NODE_MESSAGE,
  ensureSupportedNode,
  isSupportedNode,
  parseNodeVersion,
};
