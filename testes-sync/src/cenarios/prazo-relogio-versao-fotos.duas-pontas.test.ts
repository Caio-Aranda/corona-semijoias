import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { consultar, contar, texto } from '../banco-do-servidor';
import { Cenario, SENHA_DO_VENDEDOR, sementeDaRodada, Tela } from '../cenario';
import { esperarAte } from '../esperar';

/**
 * Cenários 10 a 13 do plano (ADR-0013, itens 11, 12 e 17; ESPECIFICACAO §10, §11 e §20): prazo sem internet esgotado,
 * relógio do computador errado, app abaixo da versão mínima enviando (ficou da 5.13) e fotos (troca sem internet e queda
 * no meio do download).
 */
let cenario: Cenario;

beforeAll(async () => {
  cenario = await Cenario.criar(sementeDaRodada('prazo, relógio, versão e fotos'));
});
afterAll(() => cenario?.desligar());

const DIA = 86_400_000;
const PUSH = '/api/vendedor/sync/push';
const PULL = '/api/vendedor/sync/pull';

describe('duas pontas: prazo, relógio, versão mínima e fotos', () => {
  it('10. prazo sem internet esgotado: venda nova barrada no app, o que aguardava ainda é enviado e o contato libera', async () => {
    const [peca] = await cenario.pecas(1);
    const nina = await cenario.vendedor('Nina');
    const maletaId = await cenario.dona.maleta(nina.vendedorId, { [peca!.id]: 3 });
    await nina.aparelho.sincronizar();
    await Tela.conferirTudo(nina.aparelho, maletaId);
    const clienteId = await Tela.cliente(nina.aparelho, 'Cliente da Nina');
    await nina.aparelho.sincronizar();

    nina.aparelho.semInternet();
    const antes = await Tela.vender(nina.aparelho, { maletaId, clienteId, produtoId: peca!.id });
    // Onze dias sem internet (o relógio do computador anda junto).
    nina.aparelho.relogio.deslocamentoMs = 11 * DIA;
    expect(nina.aparelho.estado().prazo.situacao).toBe('ESGOTADO');
    const barrada = await nina.aparelho.chamar({
      operacao: 'vendas.vender',
      venda: {
        id: '7f0c6d2e-0000-4000-8000-00000000000a',
        remessaId: maletaId,
        clienteId,
        itens: [{ produtoId: peca!.id, quantidade: 1 }],
        pagamentos: [],
      },
    });
    expect(barrada.ok).toBe(false);
    expect(nina.aparelho.estado().vendasAguardando).toBe(1);

    // A internet volta: a venda guardada vai, e o contato com o servidor libera vender de novo.
    nina.aparelho.comInternet();
    await nina.aparelho.sincronizar();
    expect(await contar(`SELECT count(*) FROM corona.vendas WHERE id = ${texto(antes)}`)).toBe(1);
    expect(nina.aparelho.estado().prazo.situacao).toBe('EM_DIA');
    await Tela.vender(nina.aparelho, { maletaId, clienteId, produtoId: peca!.id });

    await cenario.afirmarNoFim([nina.aparelho]);
  });

  it('11. relógio errado (3 dias adiantado e 2 atrasado): a data da venda no servidor é corrigida', async () => {
    const [peca] = await cenario.pecas(1);
    const resultados: Array<{ vendaId: string; deslocamento: number }> = [];
    const vivos = [];
    for (const deslocamento of [3 * DIA, -2 * DIA]) {
      const v = await cenario.dona.vendedor(
        `Relógio ${deslocamento > 0 ? 'Adiantado' : 'Atrasado'}`,
      );
      const a = await cenario.aparelho(deslocamento > 0 ? 'Adiantado' : 'Atrasado');
      a.vendedorId = v.vendedorId;
      a.relogio.deslocamentoMs = deslocamento;
      await a.primeiraEntrada(v.login, v.senhaTemporaria, SENHA_DO_VENDEDOR);
      const maletaId = await cenario.dona.maleta(v.vendedorId, { [peca!.id]: 2 });
      await a.sincronizar();
      await Tela.conferirTudo(a, maletaId);
      const clienteId = await Tela.cliente(a, 'Cliente do Relógio');
      a.semInternet();
      const vendaId = await Tela.vender(a, { maletaId, clienteId, produtoId: peca!.id });
      a.comInternet();
      await a.sincronizar();
      resultados.push({ vendaId, deslocamento });
      vivos.push(a);
    }
    for (const r of resultados) {
      const [v] = await consultar<{ vendida_em: string; hora_ajustada: boolean }>(
        `SELECT vendida_em, hora_ajustada FROM corona.vendas WHERE id = ${texto(r.vendaId)}`,
      );
      expect(v?.hora_ajustada).toBe(true);
      // A hora gravada é a do servidor (corrigida), não a do relógio errado.
      expect(Math.abs(Date.parse(v!.vendida_em) - Date.now())).toBeLessThan(10 * 60_000);
    }
    await cenario.afirmarNoFim(vivos);
  });

  it('12. app abaixo da versão mínima com vendas aguardando: envia sem duplicar, não recebe, e depois de atualizar recebe', async () => {
    const [peca] = await cenario.pecas(1);
    const olga = await cenario.vendedor('Olga');
    const maletaId = await cenario.dona.maleta(olga.vendedorId, { [peca!.id]: 3 });
    await olga.aparelho.sincronizar();
    await Tela.conferirTudo(olga.aparelho, maletaId);
    const clienteId = await Tela.cliente(olga.aparelho, 'Cliente da Olga');
    await olga.aparelho.sincronizar();

    olga.aparelho.semInternet();
    const v1 = await Tela.vender(olga.aparelho, { maletaId, clienteId, produtoId: peca!.id });
    const v2 = await Tela.vender(olga.aparelho, { maletaId, clienteId, produtoId: peca!.id });
    // A dona subiu a versão mínima com o app aberto: o pedido chega à API como versão 0.9.0 (mínima 1.0.0).
    const pedidosAntes = olga.aparelho.proxy.historico().length;
    olga.aparelho.comInternet({
      sorteio: [],
      cabecalhos: { 'x-app-versao': '0.9.0' },
      porCaminho: [{ caminho: PUSH, roteiro: ['CORTE_DEPOIS_DE_GRAVAR'] }],
    });
    await esperarAte(
      () => olga.aparelho.estado().aguardando === 0 && olga.aparelho.estado().appDesatualizado,
      {
        limiteMs: 60_000,
        descricao: () =>
          `enviar abaixo da versão mínima (${JSON.stringify(olga.aparelho.estado())})`,
      },
    );
    expect(
      await contar(`SELECT count(*) FROM corona.vendas WHERE id IN (${texto(v1)}, ${texto(v2)})`),
    ).toBe(2);
    const depois = olga.aparelho.proxy.historico().slice(pedidosAntes);
    // Nenhum recebimento aceito com a versão velha: ou não pediu, ou ouviu 426.
    expect(depois.filter((p) => p.caminho.startsWith(PULL) && p.statusDoServidor === 200)).toEqual(
      [],
    );

    // A atualização: o app fecha e abre de novo, com a versão nova, e a pessoa entra.
    olga.aparelho.rede({ sorteio: [] });
    await olga.aparelho.fechar();
    await olga.aparelho.reabrir();
    expect((await olga.aparelho.entrar(olga.login, SENHA_DO_VENDEDOR)).tipo).toBe('entrou');
    await cenario.afirmarNoFim([olga.aparelho]);
  });

  it('13. foto trocada sem internet e queda no meio do download: a nova chega inteira, a velha sai, e a venda sem foto não trava', async () => {
    const [comFoto] = await cenario.pecas(1, { comFoto: true });
    const [semFoto] = await cenario.pecas(1);
    const paula = await cenario.vendedor('Paula');
    const maletaId = await cenario.dona.maleta(paula.vendedorId, {
      [comFoto!.id]: 2,
      [semFoto!.id]: 2,
    });
    await paula.aparelho.sincronizar();
    await paula.aparelho.esperarFotos();
    const hashVelho = paula.aparelho.ler<{ h: string }>(
      'SELECT foto_principal_hash AS h FROM produtos WHERE id = ?',
      comFoto!.id,
    )[0]!.h;
    expect(paula.aparelho.arquivos().some((f) => f.includes(hashVelho))).toBe(true);

    // Sem internet: a dona troca a foto; o computador ainda mostra a velha e vende a peça sem foto.
    paula.aparelho.semInternet();
    const hashNovo = await cenario.dona.trocarFoto(comFoto!.id, 0x123456);
    const velha = await paula.aparelho.chamar({
      operacao: 'fotos.ver',
      hash: hashVelho,
      tamanho: 'miniatura',
    });
    expect(velha.ok).toBe(true);
    await Tela.conferirTudo(paula.aparelho, maletaId);
    const clienteId = await Tela.cliente(paula.aparelho, 'Cliente da Paula');
    await Tela.vender(paula.aparelho, { maletaId, clienteId, produtoId: semFoto!.id });

    // A internet volta e o download da foto nova cai no meio da primeira vez.
    paula.aparelho.comInternet({
      sorteio: [],
      porCaminho: [{ caminho: '/api/vendedor/fotos/', roteiro: ['CORTE_NO_MEIO_DA_RESPOSTA'] }],
    });
    await paula.aparelho.sincronizar();
    await esperarAte(
      () => {
        const arquivos = paula.aparelho.arquivos();
        return (
          arquivos.some((f) => f.includes(hashNovo) && f.endsWith('media.jpg')) &&
          !arquivos.some((f) => f.includes(hashVelho))
        );
      },
      {
        descricao: () =>
          `a foto nova chegar e a velha sair (${paula.aparelho.arquivos().join(', ')})`,
      },
    );
    const fotos = paula.aparelho.proxy
      .historico()
      .filter((p) => p.caminho.startsWith('/api/vendedor/fotos/'));
    expect(fotos.some((p) => p.falha === 'CORTE_NO_MEIO_DA_RESPOSTA')).toBe(true);
    expect(paula.aparelho.arquivos().filter((f) => f.endsWith('.parcial'))).toEqual([]);

    await cenario.afirmarNoFim([paula.aparelho]);
  });
});
