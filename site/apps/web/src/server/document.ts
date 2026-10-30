import * as Server from "foldkit/experimental/server";

const icon = encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="6" fill="#1f9d55"/><path d="M8 22V10h4l4 6 4-6h4v12h-4v-6l-4 5-4-5v6z" fill="#fff"/></svg>'
);

const themeScript =
  "<script>try{var t=localStorage.getItem('theme');if(t==='dark'||t==='light'){document.documentElement.dataset.theme=t}}catch(e){}</script>";

const head = [
  '<meta name="description" content="Port Minecraft mods between versions and check them in seconds. A command-line tool for people who write mods from source.">',
  '<meta name="color-scheme" content="light dark">',
  `<link rel="icon" type="image/svg+xml" href="data:image/svg+xml,${icon}">`,
  themeScript,
].join("\n");

export const renderDocument: Server.DocumentRenderer = (application, assets) =>
  Server.renderDocument(application, assets, { head });
