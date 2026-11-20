import { afterEach, describe, expect, it, vi } from 'vitest';
import { avisoEmEspanhol } from './es/avisos-da-dona';
import { mensagemDaApi } from './erros';
import { definirIdioma } from './idioma';

afterEach(() => {
  delete (globalThis as { corona?: unknown }).corona;
  definirIdioma('pt');
  vi.resetModules();
});

/** Palavras que só existem em português e nunca devem aparecer numa tela em espanhol. */
const PORTUGUES =
  /(você|não|peças?|recebido|pendências|diferença|lidas|devolvidas|com os clientes|dinheiro)|[çãõ]/i;

/** Abre o programa no idioma pedido: o idioma guardado é lido quando os textos são carregados. */
function abrirEm(idioma: 'es' | 'pt') {
  (globalThis as { corona?: unknown }).corona = { lerIdioma: () => idioma };
  vi.resetModules();
}

describe('Fechamento da maleta em espanhol (tarefa 9.7)', () => {
  it('o relatório inteiro (título, colunas, abas, totais e avisos) sai em espanhol', async () => {
    abrirEm('es');
    const { RELATORIOS_DE_MALETA } = await import('../nucleo/relatorios-maleta');
    const d = RELATORIOS_DE_MALETA['maleta-fechamento'];
    expect(d.titulo).toBe('Cierre de la maleta');
    expect(d.abas?.map((a) => a.rotulo)).toEqual(['Piezas de la maleta', 'Dinero']);
    expect(d.colunas.map((c) => c.titulo)).toEqual([
      'Pieza',
      'Enviadas',
      'Devueltas por los clientes',
      'Vendidas',
      'Todavía con los clientes',
      'Con quién',
      'Esperadas en la maleta',
      'Leídas a la vuelta',
      'Diferencia',
    ]);
    const textos = [
      d.titulo,
      d.explicacao,
      d.nota ?? '',
      d.vazio,
      ...d.colunas.map((c) => c.titulo),
      ...(d.abas ?? []).map((a) => a.rotulo),
      ...Object.values(d.rotulosDosTotais ?? {}),
    ];
    for (const t of textos) expect(t, t).not.toMatch(PORTUGUES);
    expect(d.rotulosDosTotais?.recebido).toBe('Valor cobrado');
    expect(d.rotulosDosTotais?.pendencias).toBe('Pendientes por cobrar');
  });

  it('em português, as duas abas se chamam Peças da maleta e Dinheiro', async () => {
    abrirEm('pt');
    const { RELATORIOS_DE_MALETA } = await import('../nucleo/relatorios-maleta');
    expect(RELATORIOS_DE_MALETA['maleta-fechamento'].abas?.map((a) => a.rotulo)).toEqual([
      'Peças da maleta',
      'Dinheiro',
    ]);
  });

  it('peça de outra maleta: em espanhol diz de qual maleta é; sem a maleta, só que não é desta', () => {
    definirIdioma('es');
    expect(
      mensagemDaApi({
        codigo: 'PECA_NAO_E_DESTA_MALETA',
        mensagem: 'Esta peça não é desta maleta. Ela está na maleta 0009.',
      }),
    ).toBe('Esta pieza no es de esta maleta. Está en la maleta 0009.');
    expect(
      mensagemDaApi({
        codigo: 'PECA_NAO_E_DESTA_MALETA',
        mensagem: 'Esta peça não é desta maleta. Confira o código da peça e da maleta.',
      }),
    ).toBe('Esta pieza no es de esta maleta. Revise el código de la pieza y el de la maleta.');
    definirIdioma('pt');
    expect(
      mensagemDaApi({
        codigo: 'PECA_NAO_E_DESTA_MALETA',
        mensagem: 'Esta peça não é desta maleta. Ela está na maleta 0009.',
      }),
    ).toBe('Esta peça não é desta maleta. Ela está na maleta 0009.');
  });
});

describe('Avisos da tela inicial em espanhol (tarefa 9.7)', () => {
  const casos: { tipo: string; titulo: string; texto: string }[] = [
    {
      tipo: 'CONFERENCIA_NAO_RODOU',
      titulo: 'A conferência de hoje não rodou',
      texto: 'A conferência automática não rodou nas últimas 26 horas. Toque em Conferir agora.',
    },
    {
      tipo: 'CONFERENCIA_NAO_RODOU',
      titulo: 'A conferência de hoje não rodou',
      texto: 'A conferência automática ainda não rodou. Toque em Conferir agora.',
    },
    {
      tipo: 'CONFERENCIA_NAO_RODOU',
      titulo: 'A conferência de hoje não rodou',
      texto:
        'Uma parte da conferência não terminou. Toque em Conferir agora; se continuar, avise o suporte.',
    },
    {
      tipo: 'CONFERENCIA_COM_DIFERENCA',
      titulo: 'A conferência de hoje achou 2 diferenças',
      texto: 'Abra a conferência para ver o que não bate. Nada foi mudado sozinho.',
    },
    {
      tipo: 'COTACAO_NAO_CHEGOU',
      titulo: 'As cotações de 17/11/2026 e 18/11/2026 não chegaram.',
      texto:
        'Os valores em Dólar e Guarani desses dias estão usando a cotação de 16/11/2026. O sistema tenta de novo sozinho. Se o aviso continuar amanhã, fale com o suporte do sistema.',
    },
    {
      tipo: 'COTACAO_NAO_CHEGOU',
      titulo: 'A cotação de hoje ainda não chegou.',
      texto:
        'Os valores aparecem só em reais por enquanto. O sistema tenta de novo sozinho. Se o aviso continuar amanhã, fale com o suporte do sistema.',
    },
    {
      tipo: 'COTACAO_NAO_CHEGOU',
      titulo: 'A cotação de 17/11/2026 não chegou.',
      texto:
        'Os valores em Dólar e Guarani desse dia estão usando a cotação de 16/11/2026. O sistema tenta de novo sozinho. Se o aviso continuar amanhã, fale com o suporte do sistema.',
    },
    {
      tipo: 'CAIXA_NEGATIVO',
      titulo: 'O caixa está negativo em R$ 20,00.',
      texto:
        'Entrou menos dinheiro do que saiu. Confira os lançamentos do caixa e lance o que faltar.',
    },
    {
      tipo: 'CREDITOS_SEM_SALDO_PARA_REVISAR',
      titulo: 'Há 2 vendas pagas com crédito que já tinha acabado.',
      texto: 'Abra Créditos dos clientes para revisar e escolha Manter ou Não aceitar.',
    },
    {
      tipo: 'DEVOLUCOES_PARA_AUTORIZAR',
      titulo: 'Há 1 pedido de autorização de devolução esperando você.',
      texto: 'Abra Devoluções para autorizar e escolha Aprovar ou Negar.',
    },
    {
      tipo: 'DEVOLUCOES_PARA_REVISAR',
      titulo: 'Há 3 devoluções para revisar.',
      texto: 'Abra Devoluções para revisar e escolha Manter ou Não aceitar.',
    },
    {
      tipo: 'MUITAS_DEVOLUCOES',
      titulo: 'Muitas devoluções de Joana Representante.',
      texto:
        'Nas últimas 4 semanas, Joana Representante devolveu 35% do que vendeu. Confira as devoluções dele.',
    },
    {
      tipo: 'MUITAS_DEVOLUCOES',
      titulo: 'Muitas devoluções de Joana Representante.',
      texto:
        'Nas últimas 4 semanas, Joana Representante teve devoluções e nenhuma venda. Confira as devoluções dele.',
    },
    {
      tipo: 'ESTOQUE_BAIXO',
      titulo: '3 produtos estão acabando.',
      texto: 'Esses produtos chegaram ao estoque mínimo. Veja a lista e faça a reposição.',
    },
    {
      tipo: 'COMPUTADOR_SEM_ENVIAR',
      titulo: 'O computador de Joana está há 5 dias sem enviar.',
      texto:
        'Peça a Joana para conectar o aplicativo à internet. As vendas feitas sem internet só chegam quando o computador conectar.',
    },
    {
      tipo: 'COMPUTADOR_NOVO',
      titulo: 'Joana entrou num computador novo.',
      texto:
        'Para Joana vender nesse computador, abra a ficha do vendedor e use Trocar computador. Se não foi Joana, use Bloquear computador.',
    },
    {
      tipo: 'ENVIOS_COM_PROBLEMA',
      titulo: 'Há 2 envios com problema.',
      texto:
        'São vendas ou cadastros feitos no aplicativo do representante que não puderam entrar. Abra Envios com problema e escolha Tentar de novo ou Descartar.',
    },
    {
      tipo: 'VENDAS_DE_COMPUTADOR_BLOQUEADO',
      titulo: 'Há 1 venda de um computador bloqueado esperando você.',
      texto:
        'Elas ainda não valem. Abra Vendas de computador bloqueado e escolha Aprovar ou Descartar.',
    },
  ];

  it.each(casos)('$tipo: "$titulo" sai inteiro em espanhol', (aviso) => {
    const es = avisoEmEspanhol(aviso);
    expect(es.titulo).not.toBe(aviso.titulo);
    expect(es.texto).not.toBe(aviso.texto);
    for (const t of [es.titulo, es.texto]) {
      expect(t, t).not.toMatch(PORTUGUES);
      expect(t).not.toMatch(/\b(Há|Peça|Abra a|Toque em Conferir|Os valores)\b/);
    }
  });

  it('mantém os números, os nomes e as datas', () => {
    const c = avisoEmEspanhol(casos[4]!);
    expect(c.titulo).toBe('Las cotizaciones de 17/11/2026 y 18/11/2026 no llegaron.');
    expect(c.texto).toContain('16/11/2026');
    expect(avisoEmEspanhol(casos[0]!).titulo).toBe('La verificación de hoy no se ejecutó');
    expect(avisoEmEspanhol(casos[11]!).texto).toContain('Joana Representante devolvió el 35%');
  });

  it('aviso que o servidor ganhar depois aparece como veio, sem quebrar', () => {
    const novo = { tipo: 'OUTRO_NOVO', titulo: 'Algo novo', texto: 'Texto novo' };
    expect(avisoEmEspanhol(novo)).toEqual(novo);
  });
});
