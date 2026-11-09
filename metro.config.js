const path = require("path");
const { getDefaultConfig } = require("expo/metro-config");

const config = getDefaultConfig(__dirname);

// モデル(.obj/.stl/.3mf)・取り付け口レイアウト(.json)をバイナリアセットとしてバンドルする
config.resolver.assetExts.push("obj", "stl", "3mf");

// three のCJS版(build/three.cjs)はトップレベルで process.emitWarning を呼ぶが、
// React Native には存在せず「undefined is not a function」で即クラッシュする。
// "three" のベア import だけ ESM版(build/three.module.js)に直接差し替える
// (package.json の exports 解決自体は他のパッケージのために有効のままにする)。
const threeEsmPath = path.resolve(__dirname, "node_modules/three/build/three.module.js");
const { resolveRequest: defaultResolveRequest } = config.resolver;
config.resolver.resolveRequest = (context, moduleName, platform) => {
  if (moduleName === "three") {
    return { type: "sourceFile", filePath: threeEsmPath };
  }
  return defaultResolveRequest
    ? defaultResolveRequest(context, moduleName, platform)
    : context.resolveRequest(context, moduleName, platform);
};

module.exports = config;
