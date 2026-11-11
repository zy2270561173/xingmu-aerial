import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
import './styles/main.less';
import './styles/tokens.less';
import './styles/aerial.less';

// The theme attribute is already present on <html> in index.html so the first paint is dark.
// Setting it again here keeps dev-server and SPA boot paths identical, and the attribute selector
// still outranks the component kit's runtime :root injection.
document.documentElement.dataset.theme = 'aerial';

createApp(App).use(createPinia()).use(router).mount('#app');
