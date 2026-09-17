export function decodeTenant(slug) {
  const cn = Buffer.from(String(slug), 'base64url').toString('utf8');
  if (!cn.startsWith('CN=')) {
    throw new Error('Tenant non valido');
  }
  return cn;
}
