import { browser } from 'webextension-polyfill-ts';

// Injects a script tag into the current document
function injectCustomJs() {
  try {
    let jsPath = 'scripts/content/inject.js';
    const container = document.head || document.documentElement;
    const scriptTag = document.createElement('script');
    scriptTag.setAttribute('type', 'text/javascript');
    scriptTag.src = browser.extension.getURL(jsPath);
    container.insertBefore(scriptTag, container.children[0]);
    scriptTag.onload = function() {
      // remove after run the script
      container.removeChild(scriptTag);
    };
  } catch (e) {
    console.error('CasperLabs provider injection failed.', e);
  }
}

// inject page -> content script -> background
function setUpProxy() {
  // forward messages from inpage to background
  window.addEventListener('message', receiveMessage, false);

  async function receiveMessage(event: MessageEvent) {
    const msg = event.data;
    console.log('receive message', msg);
    // validate message type
    if (typeof msg !== 'object') return;
    if (msg.type !== 'request') return;
    let reply = await browser.runtime.sendMessage(msg.payload);
    msg.value = reply;
    msg.type = 'reply';
    window.postMessage(msg, '*');
  }
}

injectCustomJs();
setUpProxy();
