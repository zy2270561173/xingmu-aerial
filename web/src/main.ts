import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
import './styles/main.less';
import './styles/tokens.less';
import './styles/aerial.less';

// The Aerial theme is opt-in by attribute so the base stylesheet stays intact underneath it and
// the component kit's runtime :root cannot outrank the token layer (it is injected after the app
// bundle; an attribute selector wins on specificity instead of load order).
document.documentElement.dataset.theme = 'aerial';

createApp(App).use(createPinia()).use(router).mount('#app');
