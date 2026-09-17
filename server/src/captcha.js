import crypto from 'node:crypto';
import svgCaptcha from 'svg-captcha';
import { config } from './config.js';

const TTL_MS = 10 * 60 * 1000;

function sign(payload) {
  return crypto.createHmac('sha256', config.captchaSecret).update(payload).digest('hex');
}

export function createCaptcha() {
  const c = svgCaptcha.create({ size: 5, noise: 2 });
  const answer = c.text.toLowerCase();
  const expires = Date.now() + TTL_MS;
  const payload = `${answer}.${expires}`;
  const token = Buffer.from(`${payload}.${sign(payload)}`).toString('base64url');
  return { svg: c.data, token };
}

export function verifyCaptcha(token, userAnswer) {
  try {
    const [answer, expiresStr, sig] = Buffer.from(String(token), 'base64url').toString('utf8').split('.');
    if (sign(`${answer}.${expiresStr}`) !== sig) return false;
    if (Date.now() > Number(expiresStr)) return false;
    return answer === String(userAnswer || '').toLowerCase().trim();
  } catch {
    return false;
  }
}
