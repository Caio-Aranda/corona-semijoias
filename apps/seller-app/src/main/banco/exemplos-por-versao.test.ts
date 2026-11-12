import { existsSync } from 'node:fs';
import { afterEach, describe, expect, it } from 'vitest';
import { CicloDeSincronizacao } from '../sync/ciclo';
import { criarTransporteDoSync } from '../sync/transporte-do-sync';
import { ServidorFalso } from '../sync/testes/servidor-falso';
import { abrirBancoLocal, type BancoAberto } from './banco-local';
import { MIGRACOES, VERSAO_DO_BANCO } from './migracoes';
import {
  abrirSemMigrar,
  CHAVE_DOS_EXEMPLOS,
  caminhoDoExemplo,
  copiarExemplo,
  versoesComExemplo,
} from './testes/exemplos';

/**
 * Teste obrigatório da §10 (tarefa 5.13; ADR-0013, item 16): o repositório guarda um banco de exemplo de CADA versão
 * publicada do banco local, com operações aguardando envio; o aplicativo atual abre cada um, migra e envia, e nada se
 * perde nem duplica. SQLCipher de verdade, arquivo de verdade.
 */

const PUSH = '/api/vendedor/sync/push';
const limpar: (() => void | Promise<void>)[] = [];
afterEach(async () => {
  for (const f of limpar.splice(0).reverse()) await f();
});

type LinhaDaOutbox = Record<string, unknown> & {
  operacao_id: string;
  dados: string;
  situacao: string;
};

function outbox(db: BancoAberto): LinhaDaOutbox[] {
  return db.prepare('SELECT * FROM outbox ORDER BY ordem').all() as LinhaDaOutbox[];
}

function abrirComOAppAtual(caminho: string): BancoAberto {
  const db = abrirBancoLocal({ caminho, chave: CHAVE_DOS_EXEMPLOS });
  limpar.push(() => {
    db.close();
  });
  return db;
}

async function servidor(): Promise<{ s: ServidorFalso; url: string }> {
  const s = new ServidorFalso();
  const url = await s.iniciar();
  limpar.push(() => s.parar());
  return { s, url };
}

function ciclo(db: BancoAberto, url: string): CicloDeSincronizacao {
  return new CicloDeSincronizacao({
    banco: () => db,
    transporte: criarTransporteDoSync({ baseUrl: url, versaoDoApp: '1.0.0' }),
    comToken: (fn) => fn('token-de-teste'),
    agora: () => new Date('2026-11-13T09:00:00.000Z'),
    esperar: () => Promise.resolve(),
  });
}

describe('bancos de exemplo de cada versão publicada', () => {
  it('toda versão do banco local tem o seu exemplo, e não há exemplo de versão que não existe', () => {
    expect(versoesComExemplo()).toEqual(MIGRACOES.map((m) => m.versao));
    for (const m of MIGRACOES) expect(existsSync(caminhoDoExemplo(m.versao))).toBe(true);
  });

  it('cada exemplo está mesmo na sua versão e tem operações aguardando envio', () => {
    for (const v of versoesComExemplo()) {
      const c = copiarExemplo(v);
      limpar.push(c.apagar);
      const db = abrirSemMigrar(c.caminho, v);
      limpar.push(() => {
        db.close();
      });
      expect(Number(db.pragma('user_version', { simple: true })), `v${v}`).toBe(v);
      const linhas = outbox(db);
      expect(
        linhas.filter((l) => l.situacao === 'AGUARDANDO').length,
        `v${v}`,
      ).toBeGreaterThanOrEqual(5);
      expect(new Set(linhas.map((l) => l.tipo))).toEqual(
        new Set(['VENDA', 'ESTORNO_VENDA', 'CLIENTE_NOVO', 'CLIENTE_ALTERADO', 'CONFERENCIA']),
      );
      expect(linhas.some((l) => l.situacao === 'ENVIADO')).toBe(true);
      expect(linhas.some((l) => l.situacao === 'RECUSADO')).toBe(true);
    }
  });

  for (const v of MIGRACOES.map((m) => m.versao)) {
    it(`v${v}: o app atual migra sem tocar na outbox e guarda a cópia de antes`, () => {
      const c = copiarExemplo(v);
      limpar.push(c.apagar);
      const antes = abrirSemMigrar(c.caminho, v);
      const outboxAntes = outbox(antes);
      antes.close();

      const db = abrirComOAppAtual(c.caminho);
      expect(Number(db.pragma('user_version', { simple: true }))).toBe(VERSAO_DO_BANCO);
      const migracoes = db.prepare('SELECT versao FROM _migracoes ORDER BY versao').all() as {
        versao: number;
      }[];
      expect(migracoes.map((m) => m.versao)).toEqual(MIGRACOES.map((m) => m.versao));
      // A outbox não é migrada: mesmas linhas, mesma ordem, mesmo JSON byte a byte, mesma situação.
      expect(outbox(db)).toEqual(outboxAntes);
      expect(existsSync(`${c.caminho}.antes-v${v}`)).toBe(v < VERSAO_DO_BANCO);
    });

    it(`v${v}: depois de migrar envia tudo o que aguardava, na ordem, uma vez só`, async () => {
      const c = copiarExemplo(v);
      limpar.push(c.apagar);
      const antes = abrirSemMigrar(c.caminho, v);
      const aguardando = outbox(antes).filter((l) => l.situacao === 'AGUARDANDO');
      const jaResolvidas = outbox(antes).filter((l) => l.situacao !== 'AGUARDANDO');
      antes.close();

      const db = abrirComOAppAtual(c.caminho);
      const { s, url } = await servidor();
      // A primeira resposta se perde depois de o servidor gravar (o app fechou, a rede caiu...).
      s.roteirosDoPush.push('cair-depois-de-gravar');
      expect(await ciclo(db, url).executar()).toBe('falha');
      expect(await ciclo(db, url).executar()).toBe('ok');

      const enviados = s
        .pedidosPara(PUSH)
        .flatMap((p) => (JSON.parse(p.corpo) as { operacoes: { operacaoId: string }[] }).operacoes);
      const ids = [...new Set(enviados.map((o) => o.operacaoId))];
      // Na ordem da outbox, só as que aguardavam (nunca a enviada nem a recusada).
      expect(ids).toEqual(aguardando.map((l) => l.operacao_id));
      for (const l of aguardando) {
        // O JSON gravado vai exatamente como foi guardado.
        expect(s.pedidosPara(PUSH).some((p) => p.corpo.includes(`"dados":${l.dados}`))).toBe(true);
        expect(s.gravacoes.get(l.operacao_id), l.operacao_id).toBe(1);
      }
      const depois = outbox(db);
      expect(depois.filter((l) => l.situacao === 'AGUARDANDO')).toEqual([]);
      for (const l of jaResolvidas) {
        expect(depois.find((d) => d.operacao_id === l.operacao_id)?.situacao).toBe(l.situacao);
      }
      expect(depois).toHaveLength(aguardando.length + jaResolvidas.length);

      // Um terceiro ciclo não manda nada de novo.
      const pedidos = s.pedidosPara(PUSH).length;
      expect(await ciclo(db, url).executar()).toBe('ok');
      expect(s.pedidosPara(PUSH)).toHaveLength(pedidos);
    });
  }
});
