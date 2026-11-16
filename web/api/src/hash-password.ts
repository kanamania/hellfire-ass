// Prints an OWNER_PASSWORD_HASH value for a password read from standard input.
import { hashPassword } from './auth.js';

let input = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => (input += chunk));
process.stdin.on('end', () => {
  const password = input.replace(/\r?\n$/, '');
  if (password.length < 12) {
    console.error('Use a password of at least 12 characters.');
    process.exit(1);
  }
  console.log(hashPassword(password));
});
