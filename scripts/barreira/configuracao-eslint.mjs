import { lerMarcadores } from './marcadores.mjs';
import { regraImportacaoDentroDoApp } from './regra-importacao-dentro-do-app.mjs';

const mensagemVendas =
  'O Corona Vendas não pode usar código da dona (ADR-0003). Use só o que é compartilhado em packages/ (@corona/ui, @corona/shared) ou o cliente do vendedor.';

const mensagemPacotes =
  'Os pacotes compartilhados não podem usar código de aplicativos nem os clientes da API (ADR-0003). Quem importa pacotes é o aplicativo, nunca o contrário.';

/** Padrões de importação para um nome de pacote ou pasta: `@corona/x`, `@corona/x/...`, `.../x` e `.../x/...`. */
const padroesDe = (nome) => [`@corona/${nome}`, `@corona/${nome}/*`, `**/${nome}`, `**/${nome}/**`];

/**
 * Barreira de importação, primeira camada (ADR-0003, item 4). Os nomes proibidos vêm do arquivo
 * único de marcadores. Devolve uma lista de configurações do ESLint para entrar em eslint.config.mjs.
 */
export function configuracaoDaBarreira(arquivoDeMarcadores) {
  const { importacoesProibidas } = lerMarcadores(arquivoDeMarcadores);
  const plugin = { rules: { 'importacao-dentro-do-app': regraImportacaoDentroDoApp } };

  return [
    {
      name: 'corona/barreira-corona-vendas',
      files: ['apps/seller-app/**/*.{ts,tsx,js,mjs,cjs}'],
      plugins: { corona: plugin },
      rules: {
        'no-restricted-imports': [
          'error',
          {
            patterns: [{ group: importacoesProibidas.flatMap(padroesDe), message: mensagemVendas }],
          },
        ],
        'corona/importacao-dentro-do-app': ['error', { pasta: 'apps/seller-app' }],
      },
    },
    {
      name: 'corona/barreira-packages',
      files: ['packages/**/*.{ts,tsx,js,mjs,cjs}'],
      rules: {
        'no-restricted-imports': [
          'error',
          {
            patterns: [
              {
                group: [
                  ...importacoesProibidas.flatMap(padroesDe),
                  ...padroesDe('seller-app'),
                  '@corona/api-client-*',
                  '@corona/api-client-*/*',
                  '**/api-client-*',
                  '**/api-client-*/**',
                  '**/apps/**',
                ],
                message: mensagemPacotes,
              },
            ],
          },
        ],
      },
    },
  ];
}
