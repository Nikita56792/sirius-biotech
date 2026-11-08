import { initializeNavigation, navigationCommand } from "./navigator/core.js";

async function checked(url) {
  const response = await fetch(url);
  if (!response.ok)
    throw new Error(`Не удалось загрузить карту (${response.status})`);
  return response;
}
async function initialize() {
  const base = new URL("./navigator/", import.meta.url);
  const texts = {};
  await Promise.all(
    ["places.json", "stairs.json", "walked.json"].map(async (name) => {
      texts["map/" + name] = await (
        await checked(new URL("map/" + name, base))
      ).text();
    }),
  );
  const grids = {};
  await Promise.all(
    JSON.parse(texts["map/places.json"]).floors.map(async (floor) => {
      const path = `map/nav${floor.n}.u16`;
      let buffer;
      if (typeof DecompressionStream !== "undefined") {
        const response = await checked(new URL(path + ".gz", base));
        buffer = await new Response(
          response.body.pipeThrough(new DecompressionStream("gzip")),
        ).arrayBuffer();
      } else buffer = await (await checked(new URL(path, base))).arrayBuffer();
      grids[path] = new Int16Array(buffer);
    }),
  );
  return JSON.parse(initializeNavigation(texts, grids));
}
let ready;
self.onmessage = async ({ data: message }) => {
  try {
    if (message.type === "init") {
      ready ||= initialize();
      self.postMessage({ id: message.requestId, result: await ready });
    } else {
      await ready;
      self.postMessage({
        id: message.requestId,
        result: JSON.parse(navigationCommand(JSON.stringify(message))),
      });
    }
  } catch (error) {
    if (message.type === "init") ready = null;
    self.postMessage({
      id: message.requestId,
      error: error.message || String(error),
    });
  }
};
