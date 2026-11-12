import { copyFileSync, existsSync, mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { abrirBancoLocal, type BancoAberto } from '../banco-local';
import { MIGRACOES, VERSAO_DO_BANCO } from '../migracoes';
import { OperacoesLocais } from '../../sync/operacoes-locais';
import { receberMaleta } from '../../sync/testes/dados-da-maleta';

/**
 * Bancos de exemplo de cada versão publicada do banco local (tarefa 5.13; ADR-0013, item 16). Ficam em
 * `apps/seller-app/exemplos-do-banco/`, FORA de `src/` e do pacote do Corona Vendas (o `electron-builder.yml` só leva
 * `out/**`), com uma chave de teste fixa e só dados fictícios. Só os testes usam este arquivo.
 *
 * Regra: um exemplo publicado nunca é gerado de novo (seria como editar uma migração). Versão nova do banco = exemplo
 * novo, gerado uma vez por `CORONA_GERAR_EXEMPLOS=1 npx vitest run gerar-exemplos` e versionado.
 */
export const PASTA_DOS_EXEMPLOS = fileURLToPath(
  new URL('../../../../exemplos-do-banco/', import.meta.url),
);

/** Chave de teste dos exemplos (32 bytes). Nunca a chave de ninguém: só abre estes arquivos de dados fictícios. */
export const CHAVE_DOS_EXEMPLOS = Buffer.from(
  'c0c0a5e3a1e0e5c0c0a5e3a1e0e5c0c0a5e3a1e0e5c0c0a5e3a1e0e5c0c0a5e3',
  'hex',
);

export function caminhoDoExemplo(versao: number): string {
  return join(PASTA_DOS_EXEMPLOS, `banco-v${versao}.sqlite`);
}

/** As versões que têm arquivo de exemplo na pasta. */
export function versoesComExemplo(): number[] {
  return readdirSync(PASTA_DOS_EXEMPLOS)
    .map((n) => /^banco-v(\d+)\.sqlite$/.exec(n)?.[1])
    .filter((v): v is string => v !== undefined)
    .map(Number)
    .sort((a, b) => a - b);
}

/** Copia o exemplo para uma pasta temporária (o original nunca é aberto para escrita). */
export function copiarExemplo(versao: number): { caminho: string; apagar: () => void } {
  const pasta = mkdtempSync(join(tmpdir(), `corona-exemplo-v${versao}-`));
  const caminho = join(pasta, 'banco.sqlite');
  copyFileSync(caminhoDoExemplo(versao), caminho);
  return { caminho, apagar: () => rmSync(pasta, { recursive: true, force: true }) };
}

/** Abre na versão do próprio exemplo, sem migrar (para ler o "antes"). */
export function abrirSemMigrar(caminho: string, versao: number): BancoAberto {
  return abrirBancoLocal({
    caminho,
    chave: CHAVE_DOS_EXEMPLOS,
    migracoes: MIGRACOES.filter((m) => m.versao <= versao),
  });
}

// --- geração (só pelo gerar-exemplos.test.ts, com CORONA_GERAR_EXEMPLOS=1) ---

const INICIO = Date.parse('2026-11-12T10:00:00.000Z');

/**
 * Preenche um banco da versão atual com o que um vendedor teria aguardando envio, gravado pelas operações REAIS do
 * aplicativo (o JSON exato do formato 1): duas vendas (uma com pagamento), o estorno de uma, cliente novo, cliente
 * alterado e a conferência da maleta. Mais uma operação já enviada e uma recusada ainda sem "Entendi".
 */
function preencherNaVersaoAtual(db: BancoAberto): void {
  let passo = 0;
  const ops = new OperacoesLocais({
    banco: () => db,
    agora: () => new Date(INICIO + 60_000 * passo++),
  });
  const m = receberMaleta(db);
  const id = (n: number) => `0193a000-0000-7000-8000-${n.toString(16).padStart(12, '0')}`;
  ops.vender({
    id: id(1),
    remessaId: m.remessaId,
    clienteId: m.clienteId,
    itens: [{ produtoId: m.produtoA, quantidade: 2 }],
    pagamentos: [{ id: id(2), valor: '50.00', forma: 'DINHEIRO' }],
    totalConferido: '66.66',
  });
  ops.vender({
    id: id(3),
    remessaId: m.remessaId,
    clienteId: m.clienteId,
    itens: [{ produtoId: m.produtoB, quantidade: 1 }],
    pagamentos: [],
  });
  ops.estornar({ id: id(4), vendaId: id(3), motivo: 'Cliente desistiu' });
  ops.cadastrarCliente({ id: id(5), nome: 'Bia Exemplo', telefone: '11988887777' });
  ops.alterarCliente(m.clienteId, { telefone: '11977776666' });
  ops.conferir({
    id: id(6),
    remessaId: m.remessaId,
    itens: [{ produtoId: m.produtoA, contada: 1 }],
  });
  const enviado = ops.cadastrarCliente({ id: id(7), nome: 'Caio Exemplo' });
  const recusado = ops.cadastrarCliente({ id: id(8), nome: 'Duda Exemplo' });
  db.prepare(
    "UPDATE outbox SET situacao = 'ENVIADO', codigo_do_resultado = 'ACEITO', respondido_em = ? WHERE operacao_id = ?",
  ).run(new Date(INICIO).toISOString(), enviado.operacaoId);
  db.prepare(
    `UPDATE outbox SET situacao = 'RECUSADO', codigo_do_resultado = 'DADOS_INVALIDOS', tentativas = 1,
       ultimo_erro = 'Confira os dados e tente de novo.', respondido_em = ? WHERE operacao_id = ?`,
  ).run(new Date(INICIO).toISOString(), recusado.operacaoId);
}

/** Colunas de uma tabela no banco aberto. */
function colunas(db: BancoAberto, tabela: string): string[] {
  return (db.prepare(`PRAGMA table_info(${tabela})`).all() as { name: string }[]).map(
    (c) => c.name,
  );
}

/**
 * Gera o exemplo da versão pedida. Na versão atual, pelas operações reais; numa versão anterior (exemplo que faltou),
 * gera na atual e copia para o esquema antigo o que ele tinha (as cópias do servidor, a outbox e o estado).
 */
export function gerarExemplo(versao: number): string {
  if (versao < 1 || versao > VERSAO_DO_BANCO) throw new Error(`versão ${versao} não existe`);
  const destino = caminhoDoExemplo(versao);
  if (existsSync(destino))
    throw new Error(`${destino} já existe: exemplo publicado não se gera de novo`);
  const pasta = mkdtempSync(join(tmpdir(), 'corona-gerar-'));
  try {
    const atual = abrirBancoLocal({
      caminho: join(pasta, 'atual.sqlite'),
      chave: CHAVE_DOS_EXEMPLOS,
    });
    preencherNaVersaoAtual(atual);
    if (versao === VERSAO_DO_BANCO) {
      atual.pragma('wal_checkpoint(TRUNCATE)');
      atual.close();
      copyFileSync(join(pasta, 'atual.sqlite'), destino);
      return destino;
    }
    const antigo = abrirSemMigrar(join(pasta, 'antigo.sqlite'), versao);
    const tabelas = (
      antigo
        .prepare(
          "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> '_migracoes'",
        )
        .all() as { name: string }[]
    ).map((t) => t.name);
    antigo.transaction(() => {
      for (const t of tabelas) {
        const cols = colunas(antigo, t).filter((c) => colunas(atual, t).includes(c));
        // Linha provisória sem a tabela `provisorias` (versão 1) ficaria sem dono: só as cópias do servidor vão.
        const filtro = cols.includes('origem_local') ? " WHERE origem_local = 'SERVIDOR'" : '';
        const linhas = atual
          .prepare(`SELECT ${cols.join(', ')} FROM ${t}${filtro}`)
          .all() as Record<string, unknown>[];
        const inserir = antigo.prepare(
          `INSERT INTO ${t} (${cols.join(', ')}) VALUES (${cols.map(() => '?').join(', ')})`,
        );
        for (const l of linhas) inserir.run(...cols.map((c) => l[c]));
      }
    })();
    atual.close();
    antigo.pragma('wal_checkpoint(TRUNCATE)');
    antigo.close();
    copyFileSync(join(pasta, 'antigo.sqlite'), destino);
    return destino;
  } finally {
    rmSync(pasta, { recursive: true, force: true });
  }
}
