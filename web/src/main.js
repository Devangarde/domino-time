import { createApp } from 'vue';
import { createI18n } from 'vue-i18n'
import App from './App.vue';
import './styles/main.scss';
import locales from "./locales.json"

const app = createApp(App);

const browserLocale = (navigator.language || 'en').split('-')[0];
const supported = ['en', 'it'];
const locale = supported.includes(browserLocale) ? browserLocale : 'en';

const i18n = createI18n({
	locale: locale,
	fallbackLocale: 'en',
	messages: locales
})

app.use(i18n)
app.mount('#app')