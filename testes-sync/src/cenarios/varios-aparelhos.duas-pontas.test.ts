import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { contar, consultar, texto } from '../banco-do-servidor';
import { Cenario, SENHA_DO_VENDEDOR, sementeDaRodada, Tela } from '../cenario';
import { esperarAte } from '../esperar';
import type { Aparelho } from '../aparelho';
import { criarSorteio } from '../sorteio';

/**
 * Cenários 5 a 9 do plano (ADR-0013, itens 8, 13 e 14; ESPECIFICACAO §10): dois vendedores ao mesmo tempo, segundo
 * computador e troca, computador bloqueado com vendas aguardando (quarentena e apagar), bloqueado sem nada aguardando e
 * com a quarentena sem resposta, e CPF repetido.
 */
let cenario: Cenario;

beforeAll(async () => {
  cenario = await Cenario.criar(sementeDaRodada('vários aparelhos'));
});
afterAll(() => cenario?.desligar());

const QUARENTENA = '/api/vendedor/sync/quarentena';

/** CPF válido (dígitos verificadores certos), a partir de um número. */
function cpfValido(n: number): string {
  const base = String(100_000_000 + (n % 800_000_000))
    .slice(0, 9)
    .split('')
    .map(Number);
  const dv = (d: number[]) => {
    const soma = d.reduce((s, x, i) => s + x * (d.length + 1 - i), 0);
    const r = (soma * 10) % 11;
    return r === 10 ? 0 : r;
  };
  const d1 = dv(base);
  const d2 = dv([...base, d1]);
  const t = [...base, d1, d2].join('');
  return `${t.slice(0, 3)}.${t.slice(3, 6)}.${t.slice(6, 9)}-${t.slice(9)}`;
}

async function aparelhoNaDona(vendedorId: string, situacao: string): Promise<string> {
  const lista = await cenario.dona.aparelhos(vendedorId);
  const achado = lista.find((a) => a.situacao === situacao);
  if (!achado)
    throw new Error(`Nenhum computador ${situacao} do vendedor: ${JSON.stringify(lista)}`);
  return achado.id;
}

async function esperarDesligado(a: Aparelho, limiteMs = 60_000): Promise<void> {
  await esperarAte(() => a.avisos.some((x) => x.estado === 'desligado'), {
    limiteMs,
    descricao: () => `${a.nome} apagar os dados (avisos: ${JSON.stringify(a.avisos)})`,
  });
}

describe('duas pontas: vários computadores', () => {
  it('5. dois vendedores ao mesmo tempo, com a dona mudando preço e foto: cada um recebe só o que é dele', async () => {
    const [comum, deA, deB] = await cenario.pecas(3, { comFoto: true });
    const a = await cenario.vendedor('Elisa');
    const b = await cenario.vendedor('Fabi');
    const maletaA = await cenario.dona.maleta(a.vendedorId, { [comum!.id]: 5, [deA!.id]: 5 });
    const maletaB = await cenario.dona.maleta(b.vendedorId, { [comum!.id]: 5, [deB!.id]: 5 });
    const redeLeve = {
      sorteio: [
        { falha: 'QUEDA_ANTES' as const, peso: 1 },
        { falha: 'ERRO_502' as const, peso: 1 },
        { falha: 'CORTE_DEPOIS_DE_GRAVAR' as const, peso: 2 },
      ],
      chanceDeFalha: 0.15,
      atrasoMs: { minimo: 0, maximo: 40 },
    };
    a.aparelho.rede(redeLeve);
    b.aparelho.rede(redeLeve);
    await Promise.all([a.aparelho.sincronizar(120_000), b.aparelho.sincronizar(120_000)]);
    await Tela.conferirTudo(a.aparelho, maletaA);
    await Tela.conferirTudo(b.aparelho, maletaB);
    const clienteA = await Tela.cliente(a.aparelho, 'Cliente da Elisa');
    const clienteB = await Tela.cliente(b.aparelho, 'Cliente da Fabi');

    const sorteio = criarSorteio(cenario.semente);
    for (let i = 0; i < 12; i++) {
      const quem = sorteio() < 0.5 ? a : b;
      const maleta = quem === a ? maletaA : maletaB;
      const cliente = quem === a ? clienteA : clienteB;
      const peca = sorteio() < 0.5 ? comum! : quem === a ? deA! : deB!;
      const saldo = quem.aparelho.ler<{ s: number }>(
        `SELECT (SELECT qtd_enviada FROM itens_remessa WHERE remessa_id = ? AND produto_id = ?)
              + coalesce((SELECT sum(quantidade) FROM movimentos_remessa WHERE remessa_id = ? AND produto_id = ?), 0) AS s`,
        maleta,
        peca.id,
        maleta,
        peca.id,
      )[0]!.s;
      if (saldo > 0)
        await Tela.vender(quem.aparelho, {
          maletaId: maleta,
          clienteId: cliente,
          produtoId: peca.id,
        });
      if (i === 4) await cenario.dona.mudarPreco(comum!.id, '61.00');
      if (i === 8) await cenario.dona.trocarFoto(comum!.id, 0xabcdef);
      await new Promise((ok) => setTimeout(ok, 50));
    }

    await cenario.afirmarNoFim([a.aparelho, b.aparelho]);
    // O preço novo e a foto nova chegaram aos dois.
    for (const v of [a, b]) {
      const [p] = v.aparelho.ler<{ preco_venda: string; foto_principal_hash: string }>(
        'SELECT preco_venda, foto_principal_hash FROM produtos WHERE id = ?',
        comum!.id,
      );
      expect(p?.preco_venda).toBe('61.00');
      const [doServidor] = await consultar<{ h: string }>(
        `SELECT foto_principal_hash AS h FROM corona.produtos WHERE id = ${texto(comum!.id)}`,
      );
      expect(p?.foto_principal_hash).toBe(doServidor?.h);
    }
  });

  it('5b. cliente cadastrado sem internet por quem sincroniza por último fica só com ela, mesmo com a volta de quem sincronizou antes (issue #130)', async () => {
    // A Ana tem o relógio sem aceleração: a volta automática dela (60 s) não cai por acaso entre o envio da Bia e a
    // comparação, que era o que fazia a falha da issue aparecer só "às vezes".
    const ana = await cenario.vendedor('Ana', { aceleracao: 1 });
    const bia = await cenario.vendedor('Bia');
    await Promise.all([ana.aparelho.sincronizar(), bia.aparelho.sincronizar()]);

    // A Bia cadastra um cliente sem internet; ele fica aguardando no computador dela.
    bia.aparelho.semInternet();
    const clienteDaBia = await Tela.cliente(bia.aparelho, 'Cliente da Bia');
    expect(bia.aparelho.estado().aguardando).toBe(1);

    // A Ana vem antes da Bia na lista: a afirmação final tem de entregar a ela o cliente que a Bia só envia depois.
    await cenario.afirmarNoFim([ana.aparelho, bia.aparelho]);
    expect(
      await contar(`SELECT count(*) FROM corona.clientes WHERE id = ${texto(clienteDaBia)}`),
    ).toBe(1);
    // A lista é só de cada representante (ADR-0016, item 4): o cliente da Bia não chega à Ana.
    expect(ana.aparelho.ler('SELECT id FROM clientes WHERE id = ?', clienteDaBia)).toHaveLength(0);
  });

  it('6. segundo computador e troca: o novo espera a dona, o antigo ainda envia o que guardou e depois apaga tudo', async () => {
    const [peca] = await cenario.pecas(1);
    const gabi = await cenario.vendedor('Gabi');
    const b1 = gabi.aparelho;
    const maletaId = await cenario.dona.maleta(gabi.vendedorId, { [peca!.id]: 2 });
    await b1.sincronizar();
    await Tela.conferirTudo(b1, maletaId);
    const clienteId = await Tela.cliente(b1, 'Cliente da Gabi');
    await b1.sincronizar();

    // O antigo vende sem internet (antes da troca).
    b1.semInternet();
    const vendaAntes = await Tela.vender(b1, { maletaId, clienteId, produtoId: peca!.id });

    // O computador novo entra (o login funciona), mas espera a dona: enviar e receber dão 403.
    const b2 = await cenario.aparelho('Gabi-2');
    b2.vendedorId = gabi.vendedorId;
    expect(await b2.entrar(gabi.login, SENHA_DO_VENDEDOR)).toEqual({ tipo: 'entrou' });
    await esperarAte(
      () =>
        b2.proxy
          .historico()
          .some(
            (p) => p.caminho.startsWith('/api/vendedor/sync/pull') && p.statusDoServidor === 403,
          ),
      { descricao: 'o computador novo ouvir 403 ao receber' },
    );
    expect(b2.ler('SELECT id FROM remessas')).toHaveLength(0);

    // A dona troca o computador: a maleta passa para o novo.
    await cenario.dona.liberar(await aparelhoNaDona(gabi.vendedorId, 'AGUARDANDO_LIBERACAO'));
    await b2.sincronizar();
    expect(b2.ler('SELECT id FROM remessas WHERE id = ?', maletaId)).toHaveLength(1);
    // O novo vende a última peça antes de o antigo enviar a dele.
    const vendaDoNovo = await Tela.vender(b2, { maletaId, clienteId, produtoId: peca!.id });
    await b2.sincronizar();

    // O antigo, ainda sem internet, vende de novo (ele não sabe da troca): agora não há saldo no servidor.
    const vendaDepois = await Tela.vender(b1, { maletaId, clienteId, produtoId: peca!.id }).catch(
      () => null,
    );
    b1.comInternet();
    await esperarDesligado(b1);

    // A venda de antes da troca entrou uma vez; a outra (sem saldo) foi recusada e está com a dona.
    expect(await contar(`SELECT count(*) FROM corona.vendas WHERE id = ${texto(vendaAntes)}`)).toBe(
      1,
    );
    expect(
      await contar(`SELECT count(*) FROM corona.vendas WHERE id = ${texto(vendaDoNovo)}`),
    ).toBe(1);
    if (vendaDepois) {
      expect(
        await contar(`SELECT count(*) FROM corona.vendas WHERE id = ${texto(vendaDepois)}`),
      ).toBe(0);
      const problemas = await cenario.dona.enviosComProblema();
      expect(problemas.find((p) => p.id === vendaDepois)).toBeTruthy();
    }
    // O antigo apagou tudo (nenhum arquivo sobra) e o servidor o vê como trocado.
    expect(b1.arquivos()).toEqual([]);
    expect((await cenario.dona.aparelhos(gabi.vendedorId)).map((x) => x.situacao).sort()).toEqual([
      'ATIVO',
      'SUBSTITUIDO',
    ]);

    await cenario.afirmarNoFim([b2]);
  });

  it('6b. troca com o último envio na rede: o que o antigo grava nesse meio-tempo chega ao servidor antes de apagar (issue #127)', async () => {
    const [peca] = await cenario.pecas(1);
    const nina = await cenario.vendedor('Nina');
    const b1 = nina.aparelho;
    const maletaId = await cenario.dona.maleta(nina.vendedorId, { [peca!.id]: 3 });
    await b1.sincronizar();
    await Tela.conferirTudo(b1, maletaId);
    const clienteId = await Tela.cliente(b1, 'Cliente da Nina');
    await b1.sincronizar();

    // O antigo vende sem internet; a dona troca o computador.
    b1.semInternet();
    const vendaAntes = await Tela.vender(b1, { maletaId, clienteId, produtoId: peca!.id });
    const b2 = await cenario.aparelho('Nina-2');
    b2.vendedorId = nina.vendedorId;
    expect(await b2.entrar(nina.login, SENHA_DO_VENDEDOR)).toEqual({ tipo: 'entrou' });
    await cenario.dona.liberar(await aparelhoNaDona(nina.vendedorId, 'AGUARDANDO_LIBERACAO'));
    await b2.sincronizar();

    // A internet volta no antigo. O 1º envio (a venda de antes, "nada mais aguardando") fica na rede, e nesse meio-tempo
    // o vendedor, que não sabe da troca, cadastra um cliente e vende. A resposta desse envio traz a ordem de apagar.
    const segurado: { aguardandoDepoisDeste?: number; cliente?: string; venda?: string } = {};
    let segurou = false;
    b1.comInternet({
      sorteio: [],
      antesDeRepassar: async ({ caminho, corpo }) => {
        if (segurou || !caminho.startsWith('/api/vendedor/sync/push')) return;
        segurou = true;
        segurado.aguardandoDepoisDeste = (
          JSON.parse(corpo.toString('utf8')) as { aguardandoDepoisDeste: number }
        ).aguardandoDepoisDeste;
        segurado.cliente = await Tela.cliente(b1, 'Cliente Novo da Nina');
        segurado.venda = await Tela.vender(b1, { maletaId, clienteId, produtoId: peca!.id });
      },
    });
    await esperarDesligado(b1);

    expect(segurado.aguardandoDepoisDeste).toBe(0);
    // As três chegaram ao servidor (a venda do antigo depois da troca é aceita pela cadeia de trocas), e só então apagou.
    for (const venda of [vendaAntes, segurado.venda!]) {
      expect(await contar(`SELECT count(*) FROM corona.vendas WHERE id = ${texto(venda)}`)).toBe(1);
    }
    expect(
      await contar(`SELECT count(*) FROM corona.clientes WHERE id = ${texto(segurado.cliente!)}`),
    ).toBe(1);
    expect(
      b1.proxy.historico().some((p) => p.caminho.startsWith('/api/vendedor/sync/quarentena')),
    ).toBe(false);
    expect(b1.arquivos()).toEqual([]);

    // Sem perda aceita: o livro inteiro do caso confere.
    await cenario.afirmarNoFim([b2]);
  });

  it('7. computador bloqueado com vendas aguardando: quarentena (com corte), apaga, e a dona aprova uma e descarta outra', async () => {
    const [peca] = await cenario.pecas(1);
    const helo = await cenario.vendedor('Helo', { aceleracao: 100 });
    const maletaId = await cenario.dona.maleta(helo.vendedorId, { [peca!.id]: 3 });
    await helo.aparelho.sincronizar();
    await Tela.conferirTudo(helo.aparelho, maletaId);
    const clienteId = await Tela.cliente(helo.aparelho, 'Cliente da Helo');
    await helo.aparelho.sincronizar();

    helo.aparelho.semInternet();
    const venda1 = await Tela.vender(helo.aparelho, { maletaId, clienteId, produtoId: peca!.id });
    const venda2 = await Tela.vender(helo.aparelho, { maletaId, clienteId, produtoId: peca!.id });
    await cenario.dona.bloquear(await aparelhoNaDona(helo.vendedorId, 'ATIVO'));

    helo.aparelho.comInternet({
      sorteio: [],
      porCaminho: [{ caminho: QUARENTENA, roteiro: ['CORTE_DEPOIS_DE_GRAVAR'] }],
    });
    await esperarDesligado(helo.aparelho);

    const quarentena = helo.aparelho.proxy
      .historico()
      .filter((p) => p.caminho.startsWith(QUARENTENA));
    expect(quarentena[0]).toMatchObject({
      falha: 'CORTE_DEPOIS_DE_GRAVAR',
      servidorRespondeu: true,
    });
    expect(quarentena.length).toBeGreaterThanOrEqual(2);
    expect(helo.aparelho.arquivos()).toEqual([]);

    // Guardadas uma vez cada, nada aplicado antes da aprovação.
    const guardadas = await consultar<{ id: string; situacao: string }>(
      `SELECT id::text, situacao FROM corona.envios_em_quarentena WHERE vendedor_id = ${texto(helo.vendedorId)} ORDER BY id`,
    );
    expect(guardadas.map((g) => g.id).sort()).toEqual([venda1, venda2].sort());
    expect(
      await contar(
        `SELECT count(*) FROM corona.vendas WHERE id IN (${texto(venda1)}, ${texto(venda2)})`,
      ),
    ).toBe(0);

    await cenario.dona.aprovarQuarentena(venda1);
    await cenario.dona.descartarQuarentena(venda2);
    expect(await contar(`SELECT count(*) FROM corona.vendas WHERE id = ${texto(venda1)}`)).toBe(1);
    expect(await contar(`SELECT count(*) FROM corona.vendas WHERE id = ${texto(venda2)}`)).toBe(0);
    // Aprovar de novo não duplica.
    await cenario.dona.aprovarQuarentena(venda1).catch(() => undefined);
    expect(await contar(`SELECT count(*) FROM corona.vendas WHERE id = ${texto(venda1)}`)).toBe(1);

    await cenario.afirmarNoFim([]);
  });

  it('8. bloqueado sem nada aguardando apaga direto; com a quarentena sem resposta, apaga depois das 3 tentativas', async () => {
    const [peca] = await cenario.pecas(1);
    const ines = await cenario.vendedor('Ines', { aceleracao: 100 });
    const maletaI = await cenario.dona.maleta(ines.vendedorId, { [peca!.id]: 1 });
    await ines.aparelho.sincronizar();
    await cenario.dona.bloquear(await aparelhoNaDona(ines.vendedorId, 'ATIVO'));
    ines.aparelho.comInternet();
    await esperarDesligado(ines.aparelho);
    expect(ines.aparelho.proxy.historico().some((p) => p.caminho.startsWith(QUARENTENA))).toBe(
      false,
    );
    expect(ines.aparelho.arquivos()).toEqual([]);
    expect(maletaI).toBeTruthy();

    const juca = await cenario.vendedor('Juca', { aceleracao: 100 });
    const maletaJ = await cenario.dona.maleta(juca.vendedorId, { [peca!.id]: 1 });
    await juca.aparelho.sincronizar();
    await Tela.conferirTudo(juca.aparelho, maletaJ);
    const clienteId = await Tela.cliente(juca.aparelho, 'Cliente do Juca');
    await juca.aparelho.sincronizar();
    juca.aparelho.semInternet();
    await Tela.vender(juca.aparelho, { maletaId: maletaJ, clienteId, produtoId: peca!.id });
    await cenario.dona.bloquear(await aparelhoNaDona(juca.vendedorId, 'ATIVO'));
    // A internet volta para tudo, menos para a quarentena.
    juca.aparelho.comInternet({
      sorteio: [],
      porCaminho: [
        {
          caminho: QUARENTENA,
          roteiro: ['QUEDA_ANTES', 'QUEDA_ANTES', 'QUEDA_ANTES', 'QUEDA_ANTES'],
        },
      ],
    });
    await esperarDesligado(juca.aparelho, 90_000);
    expect(
      juca.aparelho.proxy.historico().filter((p) => p.caminho.startsWith(QUARENTENA)).length,
    ).toBe(3);
    expect(juca.aparelho.arquivos()).toEqual([]);
    // Risco aceito pelo ADR-0013 (item 14): a venda guardada se perde.
    cenario.livro.aceitarPerda(juca.aparelho.nome);

    await cenario.afirmarNoFim([]);
  });

  it('9. CPF repetido sem internet nos dois: o segundo chega em conflito e o cliente de um nunca chega ao outro', async () => {
    const lia = await cenario.vendedor('Lia');
    const mel = await cenario.vendedor('Mel');
    await Promise.all([lia.aparelho.sincronizar(), mel.aparelho.sincronizar()]);
    const cpf = cpfValido(Math.floor(criarSorteio(cenario.semente + 9)() * 799_999_999));
    lia.aparelho.semInternet();
    mel.aparelho.semInternet();
    const daLia = await Tela.cliente(lia.aparelho, 'Cliente Repetido', cpf);
    const daMel = await Tela.cliente(mel.aparelho, 'Cliente Repetido Também', cpf);

    lia.aparelho.comInternet();
    await lia.aparelho.sincronizar();
    mel.aparelho.comInternet();
    await mel.aparelho.sincronizar();

    expect(await contar(`SELECT count(*) FROM corona.clientes WHERE id = ${texto(daLia)}`)).toBe(1);
    expect(await contar(`SELECT count(*) FROM corona.clientes WHERE id = ${texto(daMel)}`)).toBe(0);
    const daMelNoComputador = mel.aparelho.outbox().find((o) => o.operacao_id === daMel);
    expect(daMelNoComputador?.situacao).toBe('CONFLITO');
    expect((await cenario.dona.enviosComProblema()).some((p) => p.id === daMel)).toBe(true);

    // Cada representante tem só a SUA lista (ADR-0016, item 4): o cliente da Lia nunca chega ao computador da Mel.
    expect(mel.aparelho.ler('SELECT id FROM clientes WHERE id = ?', daLia)).toHaveLength(0);

    await cenario.afirmarNoFim([lia.aparelho, mel.aparelho]);
  });
});
