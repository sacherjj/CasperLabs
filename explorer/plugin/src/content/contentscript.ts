import { browser } from 'webextension-polyfill-ts';

// Injects a script tag into the current document
function injectCustomJs() {
  try {
    console.log("inject");
    let jsPath = 'scripts/content/inject.js';
    const container = document.head || document.documentElement;
    const scriptTag = document.createElement('script');
    scriptTag.setAttribute('type', 'text/javascript');
    scriptTag.src = browser.extension.getURL(jsPath);
    container.insertBefore(scriptTag, container.children[0]);
    scriptTag.onload = function() {
      // container.removeChild(scriptTag);
    };
  } catch (e) {
    console.error('CasperLabs provider injection failed.', e);
  }
}

injectCustomJs();
