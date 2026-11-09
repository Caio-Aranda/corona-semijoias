// Redefine a senha de um administrador em DESENVOLVIMENTO (comando do servidor, ADR-0004, item 8).
// Uso: npm run api:redefinir-senha -- maria.silva      (ou --login=maria.silva)
// Em produção o comando roda no contêiner da API: veja docs/redefinir-senha-da-dona.md.
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const backend = join(dirname(fileURLToPath(import.meta.url)), '..', 'backend');
const windows = process.platform === 'win32';

const argumento = process.argv[2] ?? '';
const login = argumento.startsWith('--login=') ? argumento.slice('--login='.length) : argumento;
if (login.trim() === '') {
  console.error(
    'Informe o login do administrador. Exemplo: npm run api:redefinir-senha -- maria.silva',
  );
  process.exit(2);
}
if (/[\s,"]/.test(login)) {
  console.error('O login não pode ter espaços, vírgulas nem aspas.');
  process.exit(2);
}

const resultado = spawnSync(
  windows ? '.\\mvnw.cmd' : './mvnw',
  // Os argumentos do programa vão separados por vírgula (regra do plugin do Spring Boot).
  [
    '-q',
    '-B',
    'spring-boot:run',
    `-Dspring-boot.run.arguments=redefinir-senha-admin,--login=${login}`,
  ],
  { cwd: backend, stdio: 'inherit', shell: windows },
);

if (resultado.error) {
  console.error(`Não foi possível rodar o Maven: ${resultado.error.message}`);
  process.exit(1);
}
process.exit(resultado.status ?? 1);
