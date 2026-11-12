import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { limparPrazo, textoDeGravacaoBloqueada, textoDoPrazo } from '../../shared/prazo';
import { CHAVES, gravarEstado, registrarContato } from '../sync/estado-sync';
import { type BancoDeTeste, criarBancoDeTeste } from '../sync/testes/banco-de-teste';
import { avaliarPrazo, ControleDoPrazo, DIA_MS } from './prazo-offline';

/**
 * Prazo máximo sem internet (tarefa 5.8; ADR-0013, item 12): dias pela hora local do último contato, relógio adiantado,
 * atrasado, voltado para trás e mudado no meio. O relógio é sempre injetado; banco de verdade (SQLCipher).
 */
const C = new Date('2026-11-01T12:00:00.000Z'); // hora local do último contato
const depois = (ms: number) => new Date(C.getTime() + ms);

describe('avaliarPrazo (a regra pura)', () => {
  const base = { contatoLocal: C, maiorHoraVista: C, prazoDias: 10 };

  it('em dia durante os primeiros 6 dias, sem aviso', () => {
    const r = avaliarPrazo({ ...base, agora: depois(6 * DIA_MS) });
    expect(r).toEqual({ situacao: 'EM_DIA', diasRestantes: 0, motivo: null });
  });

  it('avisa a partir de 3 dias antes do fim e conta os dias que faltam', () => {
    expect(avaliarPrazo({ ...base, agora: depois(7 * DIA_MS) })).toEqual({
      situacao: 'AVISO',
      diasRestantes: 3,
      motivo: null,
    });
    expect(avaliarPrazo({ ...base, agora: depois(7 * DIA_MS + 1000) }).diasRestantes).toBe(3);
    expect(avaliarPrazo({ ...base, agora: depois(8 * DIA_MS + 1000) }).diasRestantes).toBe(2);
    expect(avaliarPrazo({ ...base, agora: depois(9 * DIA_MS + 1000) }).diasRestantes).toBe(1);
  });

  it('no limite: 9 dias 23h59m59s ainda não esgotou; 10 dias cheios esgotou', () => {
    const quase = avaliarPrazo({ ...base, agora: depois(10 * DIA_MS - 1000) });
    expect(quase.situacao).toBe('AVISO');
    expect(quase.diasRestantes).toBe(1);
    expect(avaliarPrazo({ ...base, agora: depois(10 * DIA_MS) })).toEqual({
      situacao: 'ESGOTADO',
      diasRestantes: 0,
      motivo: 'PRAZO',
    });
  });

  it('relógio adiantado só faz o prazo acabar mais cedo', () => {
    const adiantado = avaliarPrazo({
      ...base,
      agora: depois(20 * DIA_MS),
      maiorHoraVista: depois(20 * DIA_MS),
    });
    expect(adiantado).toEqual({ situacao: 'ESGOTADO', diasRestantes: 0, motivo: 'PRAZO' });
  });

  it('relógio atrasado em mais de 5 minutos da maior hora vista esgota, por causa do relógio', () => {
    const maior = depois(2 * DIA_MS);
    const r = avaliarPrazo({
      ...base,
      maiorHoraVista: maior,
      agora: new Date(maior.getTime() - 5 * 60_000 - 1),
    });
    expect(r).toEqual({ situacao: 'ESGOTADO', diasRestantes: 0, motivo: 'RELOGIO' });
  });

  it('até 5 minutos para trás é tolerado (o relógio se acerta sozinho)', () => {
    const maior = depois(2 * DIA_MS);
    const r = avaliarPrazo({
      ...base,
      maiorHoraVista: maior,
      agora: new Date(maior.getTime() - 5 * 60_000),
    });
    expect(r.situacao).toBe('EM_DIA');
  });

  it('relógio antes do último contato esgota por causa do relógio', () => {
    const r = avaliarPrazo({ ...base, agora: new Date(C.getTime() - 10 * 60_000) });
    expect(r).toEqual({ situacao: 'ESGOTADO', diasRestantes: 0, motivo: 'RELOGIO' });
  });

  it('sem contato nenhum registrado, esgotado (lado seguro)', () => {
    const r = avaliarPrazo({ ...base, contatoLocal: null, agora: C });
    expect(r.situacao).toBe('ESGOTADO');
  });

  it('prazo diferente de 10 vem do parâmetro', () => {
    expect(avaliarPrazo({ ...base, prazoDias: 5, agora: depois(5 * DIA_MS) }).situacao).toBe(
      'ESGOTADO',
    );
    expect(avaliarPrazo({ ...base, prazoDias: 5, agora: depois(2 * DIA_MS + 1000) }).situacao).toBe(
      'AVISO',
    );
  });
});

describe('ControleDoPrazo (com o banco local)', () => {
  let b: BancoDeTeste;
  let agora: Date;
  let controle: ControleDoPrazo;

  beforeEach(() => {
    b = criarBancoDeTeste();
    agora = C;
    controle = new ControleDoPrazo({ banco: () => b.db, agora: () => agora });
    registrarContato(b.db, '2026-11-01T15:00:00.000Z', C); // a hora do servidor não entra na conta local
  });
  afterEach(() => b.fechar());

  it('conta os dias pela hora local do contato, nunca pela hora do servidor', () => {
    agora = depois(8 * DIA_MS);
    expect(controle.situacao()).toMatchObject({ situacao: 'AVISO', diasRestantes: 2 });
  });

  it('usa o prazo que o servidor mandou nos parâmetros', () => {
    gravarEstado(b.db, CHAVES.parametros, JSON.stringify({ tempoMaximoOfflineDias: 4 }));
    agora = depois(4 * DIA_MS);
    expect(controle.situacao().situacao).toBe('ESGOTADO');
  });

  it('a maior hora vista sobe a cada batida e nunca desce', () => {
    agora = depois(1 * DIA_MS);
    controle.batida();
    agora = depois(3600_000); // o relógio voltou
    controle.batida();
    expect(b.db.prepare('SELECT valor FROM estado_sync WHERE chave = ?').get(CHAVES.maiorHoraLocal)).toEqual({
      valor: depois(1 * DIA_MS).toISOString(),
    });
    expect(controle.situacao()).toMatchObject({ situacao: 'ESGOTADO', motivo: 'RELOGIO' });
  });

  it('relógio mudado no meio: adiantado e depois corrigido esgota, e um contato novo libera', () => {
    agora = depois(2 * DIA_MS);
    controle.batida();
    agora = depois(2 * DIA_MS + 3 * DIA_MS); // alguém adiantou o relógio 3 dias
    controle.batida();
    agora = depois(2 * DIA_MS); // e acertou de novo
    expect(controle.situacao()).toMatchObject({ situacao: 'ESGOTADO', motivo: 'RELOGIO' });

    // Só uma resposta válida do servidor refaz o par e libera.
    registrarContato(b.db, '2026-11-03T12:00:00.000Z', agora);
    expect(controle.situacao().situacao).toBe('EM_DIA');
  });

  it('sobrevive ao reinício do aplicativo: tudo está no banco', () => {
    agora = depois(1 * DIA_MS);
    controle.batida();
    const outro = new ControleDoPrazo({ banco: () => b.reabrir(), agora: () => depois(1 * DIA_MS - 3_600_000) });
    expect(outro.situacao()).toMatchObject({ situacao: 'ESGOTADO', motivo: 'RELOGIO' });
  });

  it('sem banco aberto não há como provar o prazo: esgotado', () => {
    const fechado = new ControleDoPrazo({ banco: () => null, agora: () => C });
    expect(fechado.situacao().situacao).toBe('ESGOTADO');
  });
});

describe('textos do prazo (glossário)', () => {
  it('aviso, esgotado e relógio errado usam palavras da loja', () => {
    const textos = [
      textoDoPrazo({ situacao: 'AVISO', diasRestantes: 3, motivo: null }),
      textoDoPrazo({ situacao: 'AVISO', diasRestantes: 1, motivo: null }),
      textoDoPrazo({ situacao: 'ESGOTADO', diasRestantes: 0, motivo: 'PRAZO' }),
      textoDoPrazo({ situacao: 'ESGOTADO', diasRestantes: 0, motivo: 'RELOGIO' }),
    ].flatMap((t) => (t ? [t.titulo, t.texto] : []));
    textos.push(textoDeGravacaoBloqueada('PRAZO'), textoDeGravacaoBloqueada('RELOGIO'));
    for (const t of textos) {
      expect(t).not.toMatch(/sincroniza|fila|pendente|offline|sess[ãa]o|token|quarentena/i);
    }
  });

  it('relógio errado diz em português simples para conferir a data e conectar à internet', () => {
    const t = textoDoPrazo({ situacao: 'ESGOTADO', diasRestantes: 0, motivo: 'RELOGIO' });
    expect(t?.texto).toMatch(/Confira a data e a hora do computador/);
    expect(t?.texto).toMatch(/conecte-se à internet/);
    expect(textoDeGravacaoBloqueada('RELOGIO')).toMatch(/Confira a data e a hora do computador/);
  });

  it('em dia não mostra nada; "hoje" no último dia', () => {
    expect(textoDoPrazo({ situacao: 'EM_DIA', diasRestantes: 0, motivo: null })).toBeNull();
    expect(textoDoPrazo({ situacao: 'AVISO', diasRestantes: 1, motivo: null })?.texto).toMatch(
      /^Hoje este computador precisa de internet/,
    );
  });

  it('limparPrazo copia só os campos documentados', () => {
    expect(limparPrazo({ situacao: 'ESGOTADO', motivo: 'RELOGIO', diasRestantes: 4, x: 1 })).toEqual({
      situacao: 'ESGOTADO',
      diasRestantes: 4,
      motivo: 'RELOGIO',
    });
    expect(limparPrazo('lixo')).toEqual({ situacao: 'EM_DIA', diasRestantes: 0, motivo: null });
  });
});
