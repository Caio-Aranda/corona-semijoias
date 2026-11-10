import { dirname, isAbsolute, relative, resolve } from 'node:path';

const MENSAGEM =
  'Este arquivo importa algo de fora da pasta do aplicativo ({{caminho}}). O Corona Vendas só pode usar a própria pasta e os pacotes compartilhados (@corona/ui, @corona/shared). Veja ADR-0003.';

const MENSAGEM_ABSOLUTO =
  'Importação por caminho absoluto ({{caminho}}) não é permitida no Corona Vendas. Use o nome do pacote compartilhado ou um caminho dentro da pasta do aplicativo. Veja ADR-0003.';

/**
 * Regra local: dentro de `pasta` (relativa à raiz do projeto), nenhum import, export-from,
 * import() nem require() pode apontar para um caminho que saia dessa pasta. Cobre o "../../outro-app".
 * Nomes de pacote (sem "." no começo) são tratados por no-restricted-imports.
 */
export const regraImportacaoDentroDoApp = {
  meta: {
    type: 'problem',
    schema: [
      {
        type: 'object',
        properties: { pasta: { type: 'string' } },
        required: ['pasta'],
        additionalProperties: false,
      },
    ],
    messages: { fora: MENSAGEM, absoluto: MENSAGEM_ABSOLUTO },
  },
  create(context) {
    const raiz = resolve(context.cwd, context.options[0].pasta);
    const pastaDoArquivo = dirname(context.filename);

    function conferir(no, valor) {
      if (typeof valor !== 'string') return;
      if (valor.startsWith('node:')) return;
      if (isAbsolute(valor) || /^[a-z][a-z0-9+.-]*:/i.test(valor)) {
        context.report({ node: no, messageId: 'absoluto', data: { caminho: valor } });
        return;
      }
      if (!valor.startsWith('.')) return;
      const alvo = relative(raiz, resolve(pastaDoArquivo, valor));
      if (alvo === '..' || alvo.startsWith('..\\') || alvo.startsWith('../') || isAbsolute(alvo)) {
        context.report({ node: no, messageId: 'fora', data: { caminho: valor } });
      }
    }

    const comOrigem = (no) => {
      if (no.source) conferir(no.source, no.source.value);
    };

    return {
      ImportDeclaration: comOrigem,
      ExportAllDeclaration: comOrigem,
      ExportNamedDeclaration: comOrigem,
      ImportExpression(no) {
        if (no.source.type === 'Literal') conferir(no.source, no.source.value);
      },
      CallExpression(no) {
        if (no.callee.type === 'Identifier' && no.callee.name === 'require') {
          const [primeiro] = no.arguments;
          if (primeiro?.type === 'Literal') conferir(primeiro, primeiro.value);
        }
      },
    };
  },
};
