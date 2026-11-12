import { existsSync, mkdirSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { VERSAO_DO_BANCO } from './migracoes';
import { caminhoDoExemplo, gerarExemplo, PASTA_DOS_EXEMPLOS } from './testes/exemplos';

/**
 * Gerador dos bancos de exemplo (tarefa 5.13). Não roda no `npm test`: só com `CORONA_GERAR_EXEMPLOS=1`, e só cria os
 * exemplos que FALTAM (um exemplo publicado nunca é gerado de novo). Uso, depois de criar a migração local N:
 *
 *     CORONA_GERAR_EXEMPLOS=1 npx vitest run gerar-exemplos   (na pasta apps/seller-app)
 *
 * e versionar o `exemplos-do-banco/banco-vN.sqlite` novo.
 */
describe.runIf(process.env['CORONA_GERAR_EXEMPLOS'] === '1')(
  'gerar os bancos de exemplo que faltam',
  () => {
    it('cria um arquivo para cada versão do banco local que ainda não tem', () => {
      mkdirSync(PASTA_DOS_EXEMPLOS, { recursive: true });
      for (let v = 1; v <= VERSAO_DO_BANCO; v += 1) {
        if (!existsSync(caminhoDoExemplo(v))) gerarExemplo(v);
        expect(existsSync(caminhoDoExemplo(v))).toBe(true);
      }
    });
  },
);
