fetch('/worker-network').then(response => response.text()).then(value => postMessage(value));
