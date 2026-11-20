import {
  CAMPOS_DO_CLIENTE_DO_CONSIGNADO,
  FORMAS_DO_CONSIGNADO,
  OPERACOES_DO_CONSIGNADO,
  TIPOS_DE_DOCUMENTO,
  TIPOS_DE_FOTO_DO_CLIENTE,
  type AcertoParaEnviar,
  type CampoDoClienteDoConsignado,
  type ClienteDoConsignadoParaCadastrar,
  type FormaPaga,
  type LinhaParaReposicao,
  type PecaDevolvida,
  type PedidoDoConsignado,
  type PreviaDoAcertoPedida,
  type TipoDeFotoDoCliente,
} from './operacoes-do-consignado';
import type { PedidoDeRelatorio } from './relatorios-do-consignado';

/**
 * Confere na ponte o formato de cada pedido do consignado ao cliente (tarefa 9.2). Só passa o que bate com o formato; o que
 * sobra é descartado (uma cópia limpa). Quem manda é a tela; nenhuma regra de negócio mora aqui (o computador e o servidor
 * conferem de novo).
 */

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DIA = /^\d{4}-\d{2}-\d{2}$/;
const HASH = /^[0-9a-f]{64}$/;
const DINHEIRO = /^\d{1,12}(\.\d{1,2})?$/;
const PORCENTAGEM = /^\d{1,3}(\.\d{1,2})?$/;
/** Uma foto: JPEG em endereço de dados, até uns 14 MB de texto (a tela já reduz a imagem). */
const FOTO = /^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/;
const MAXIMO_DA_FOTO = 14_000_000;

type Obj = Record<string, unknown>;
const ehObjeto = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v);
const ehUuid = (v: unknown): v is string => typeof v === 'string' && UUID.test(v);
const ehTexto = (v: unknown): v is string => typeof v === 'string';
const textoAte = (v: unknown, max: number): v is string => ehTexto(v) && v.length <= max;
const ehInteiro = (v: unknown, min: number, max: number): v is number =>
  typeof v === 'number' && Number.isInteger(v) && v >= min && v <= max;

const forma = (v: unknown): FormaPaga | null => {
  if (!ehObjeto(v)) return null;
  const { forma: f, valor } = v;
  if (!ehTexto(f) || !(FORMAS_DO_CONSIGNADO as readonly string[]).includes(f)) return null;
  if (!ehTexto(valor) || !DINHEIRO.test(valor)) return null;
  return { forma: f as FormaPaga['forma'], valor };
};

const formas = (v: unknown): FormaPaga[] | null => {
  if (!Array.isArray(v) || v.length > 8) return null;
  const lista: FormaPaga[] = [];
  for (const x of v) {
    const f = forma(x);
    if (!f) return null;
    lista.push(f);
  }
  return lista;
};

const devolvidos = (v: unknown): PecaDevolvida[] | null => {
  if (!Array.isArray(v) || v.length > 500) return null;
  const lista: PecaDevolvida[] = [];
  for (const x of v) {
    if (!ehObjeto(x) || !ehUuid(x['produtoId']) || !ehUuid(x['remessaId'])) return null;
    if (!ehInteiro(x['quantidade'], 0, 100_000)) return null;
    lista.push({
      produtoId: x['produtoId'],
      remessaId: x['remessaId'],
      quantidade: x['quantidade'],
    });
  }
  return lista;
};

const previa = (v: unknown): PreviaDoAcertoPedida | null => {
  if (!ehObjeto(v) || !ehUuid(v['notaId'])) return null;
  const d = devolvidos(v['devolvidos']);
  const f = formas(v['formas']);
  const c = v['comissaoPercentual'];
  if (!d || !f || !ehTexto(c) || !PORCENTAGEM.test(c)) return null;
  const data = v['data'];
  if (data !== undefined && !(ehTexto(data) && DIA.test(data))) return null;
  return {
    notaId: v['notaId'],
    devolvidos: d,
    comissaoPercentual: c,
    formas: f,
    ...(data !== undefined ? { data } : {}),
  };
};

const opcionalTexto = (v: Obj, chave: string, max: number): string | undefined | null => {
  const x = v[chave];
  if (x === undefined || x === null) return undefined;
  return textoAte(x, max) ? x : null;
};

const cliente = (v: unknown): ClienteDoConsignadoParaCadastrar | null => {
  if (!ehObjeto(v) || !ehUuid(v['id'])) return null;
  const obrigatorios = [
    ['nome', 200],
    ['enderecoLogradouro', 200],
    ['enderecoNumero', 30],
    ['enderecoBairro', 100],
    ['enderecoCidade', 100],
    ['enderecoUf', 30],
  ] as const;
  const saida: Record<string, unknown> = { id: v['id'] };
  for (const [chave, max] of obrigatorios) {
    const x = v[chave];
    if (!textoAte(x, max)) return null;
    saida[chave] = x;
  }
  const opcionais = [
    ['enderecoComplemento', 200],
    ['enderecoCep', 20],
    ['telefone', 30],
    ['whatsapp', 30],
    ['documento', 40],
    ['dataNascimento', 10],
    ['indicadoPorNome', 200],
    ['comoConheceu', 400],
    ['observacoes', 2000],
  ] as const;
  for (const [chave, max] of opcionais) {
    const x = opcionalTexto(v, chave, max);
    if (x === null) return null;
    if (x !== undefined) saida[chave] = x;
  }
  const tipo = v['documentoTipo'];
  if (tipo !== undefined && tipo !== null) {
    if (!ehTexto(tipo) || !(TIPOS_DE_DOCUMENTO as readonly string[]).includes(tipo)) return null;
    saida['documentoTipo'] = tipo;
  }
  const indicado = v['indicadoPorClienteId'];
  if (indicado !== undefined && indicado !== null) {
    if (!ehUuid(indicado)) return null;
    saida['indicadoPorClienteId'] = indicado;
  }
  const comissao = v['comissaoPercentual'];
  if (comissao !== undefined && comissao !== null) {
    if (!ehTexto(comissao) || !PORCENTAGEM.test(comissao)) return null;
    saida['comissaoPercentual'] = comissao;
  }
  for (const chave of ['fotoHash', 'fotoDocumentoFrenteHash', 'fotoDocumentoVersoHash'] as const) {
    const x = v[chave];
    if (!ehTexto(x) || !HASH.test(x)) return null;
    saida[chave] = x;
  }
  return saida as unknown as ClienteDoConsignadoParaCadastrar;
};

const camposDoCliente = (
  v: unknown,
): Partial<Record<CampoDoClienteDoConsignado, string>> | null => {
  if (!ehObjeto(v)) return null;
  const saida: Partial<Record<CampoDoClienteDoConsignado, string>> = {};
  for (const [chave, valor] of Object.entries(v)) {
    if (!(CAMPOS_DO_CLIENTE_DO_CONSIGNADO as readonly string[]).includes(chave)) return null;
    if (!textoAte(valor, 2000)) return null;
    saida[chave as CampoDoClienteDoConsignado] = valor;
  }
  return Object.keys(saida).length > 0 ? saida : null;
};

const motivo = (v: unknown): string | null =>
  ehTexto(v) && v.trim() !== '' && v.length <= 400 ? v : null;

/** O pedido de relatório: só o tipo e os ids/datas que a pessoa escolheu. */
const relatorio = (v: unknown): PedidoDeRelatorio | null => {
  if (!ehObjeto(v)) return null;
  switch (v['tipo']) {
    case 'NOTA_DE_SAIDA':
    case 'ACERTO':
      return ehUuid(v['notaId']) ? { tipo: v['tipo'], notaId: v['notaId'] } : null;
    case 'SITUACAO_DO_CLIENTE':
      return ehUuid(v['clienteId']) ? { tipo: v['tipo'], clienteId: v['clienteId'] } : null;
    case 'PECAS_VENDIDAS': {
      const { clienteId, de, ate } = v;
      if (!ehUuid(clienteId) || !ehTexto(de) || !DIA.test(de) || !ehTexto(ate) || !DIA.test(ate))
        return null;
      return de <= ate ? { tipo: v['tipo'], clienteId, de, ate } : null;
    }
    case 'TOTAIS_DA_SEMANA': {
      const id = v['semanaId'];
      if (id === undefined) return { tipo: v['tipo'] };
      return ehUuid(id) ? { tipo: v['tipo'], semanaId: id } : null;
    }
    case 'SITUACAO_DA_MALETA':
      return ehUuid(v['remessaId']) ? { tipo: v['tipo'], remessaId: v['remessaId'] } : null;
    default:
      return null;
  }
};

export function ehOperacaoDoConsignado(operacao: string): boolean {
  return (OPERACOES_DO_CONSIGNADO as readonly string[]).includes(operacao);
}

export function validarPedidoDoConsignado(operacao: string, v: Obj): PedidoDoConsignado | null {
  switch (operacao) {
    case 'consignado.painel': {
      const id = v['semanaId'];
      if (id === undefined) return { operacao };
      return ehUuid(id) ? { operacao, semanaId: id } : null;
    }
    case 'consignado.semanaNova': {
      if (!ehUuid(v['id'])) return null;
      const n = v['numero'];
      if (n === undefined) return { operacao, id: v['id'] };
      return ehInteiro(n, 1, 9_999_999) ? { operacao, id: v['id'], numero: n } : null;
    }
    case 'consignado.clientes': {
      const b = v['busca'];
      if (b === undefined) return { operacao };
      return textoAte(b, 100) ? { operacao, busca: b } : null;
    }
    case 'consignado.cliente':
      return ehUuid(v['id']) ? { operacao, id: v['id'] } : null;
    case 'consignado.clienteCadastrar': {
      const c = cliente(v['cliente']);
      return c ? { operacao, cliente: c } : null;
    }
    case 'consignado.clienteAlterar': {
      const campos = camposDoCliente(v['campos']);
      return ehUuid(v['id']) && campos ? { operacao, id: v['id'], campos } : null;
    }
    case 'consignado.fotoGuardar': {
      const d = v['dataUrl'];
      return ehTexto(d) && d.length <= MAXIMO_DA_FOTO && FOTO.test(d)
        ? { operacao, dataUrl: d }
        : null;
    }
    case 'consignado.clienteTrocarFoto': {
      const tipo = v['tipo'];
      if (
        !ehUuid(v['id']) ||
        !ehTexto(tipo) ||
        !(TIPOS_DE_FOTO_DO_CLIENTE as readonly string[]).includes(tipo)
      )
        return null;
      return ehTexto(v['hash']) && HASH.test(v['hash'])
        ? { operacao, id: v['id'], tipo: tipo as TipoDeFotoDoCliente, hash: v['hash'] }
        : null;
    }
    case 'consignado.fotoVer':
      return ehTexto(v['hash']) && HASH.test(v['hash']) ? { operacao, hash: v['hash'] } : null;
    case 'consignado.pedidoVer':
    case 'consignado.pedidoDescartar':
    case 'consignado.maletas':
      return { operacao };
    case 'consignado.pedidoDefinir': {
      const saida: {
        operacao: 'consignado.pedidoDefinir';
        clienteId?: string | null;
        remessaId?: string | null;
        dataCobranca?: string;
      } = { operacao };
      for (const chave of ['clienteId', 'remessaId'] as const) {
        const x = v[chave];
        if (x === undefined) continue;
        if (x !== null && !ehUuid(x)) return null;
        saida[chave] = x;
      }
      const d = v['dataCobranca'];
      if (d !== undefined) {
        if (!ehTexto(d) || !DIA.test(d)) return null;
        saida.dataCobranca = d;
      }
      return saida;
    }
    case 'consignado.pedidoBipar':
      return textoAte(v['codigo'], 100) ? { operacao, codigo: v['codigo'] } : null;
    case 'consignado.pedidoQuantidade':
      return ehUuid(v['itemId']) && ehInteiro(v['quantidade'], 0, 100_000)
        ? { operacao, itemId: v['itemId'], quantidade: v['quantidade'] }
        : null;
    case 'consignado.pedidoFinalizar':
      return ehUuid(v['id']) ? { operacao, id: v['id'] } : null;
    case 'consignado.peca':
      return ehUuid(v['remessaId']) && textoAte(v['codigo'], 100)
        ? { operacao, remessaId: v['remessaId'], codigo: v['codigo'] }
        : null;
    case 'consignado.notas': {
      const t = v['texto'];
      if (t !== undefined && !textoAte(t, 100)) return null;
      const so = v['soEmAberto'];
      if (so !== undefined && typeof so !== 'boolean') return null;
      return {
        operacao,
        ...(t !== undefined ? { texto: t } : {}),
        ...(so !== undefined ? { soEmAberto: so } : {}),
      };
    }
    case 'consignado.nota':
      return ehUuid(v['id']) ? { operacao, id: v['id'] } : null;
    case 'consignado.acertoPrevia': {
      const a = previa(v['acerto']);
      return a ? { operacao, acerto: a } : null;
    }
    case 'consignado.acertoSalvar': {
      const a = previa(v['acerto']);
      const x = v['acerto'];
      if (!a || !ehObjeto(x) || !ehUuid(x['id']) || !ehUuid(x['reciboId'])) return null;
      const acerto: AcertoParaEnviar = { ...a, id: x['id'], reciboId: x['reciboId'] };
      return { operacao, acerto };
    }
    case 'consignado.reposicao': {
      const r = v['reposicao'];
      if (!ehObjeto(r) || !ehUuid(r['id']) || !ehUuid(r['notaId']) || !Array.isArray(r['itens']))
        return null;
      if (r['itens'].length === 0 || r['itens'].length > 500) return null;
      const itens: LinhaParaReposicao[] = [];
      for (const i of r['itens'] as unknown[]) {
        if (!ehObjeto(i) || !ehUuid(i['produtoId']) || !ehUuid(i['remessaId'])) return null;
        if (!ehInteiro(i['quantidade'], 1, 100_000)) return null;
        itens.push({
          produtoId: i['produtoId'],
          remessaId: i['remessaId'],
          quantidade: i['quantidade'],
        });
      }
      return { operacao, reposicao: { id: r['id'], notaId: r['notaId'], itens } };
    }
    case 'consignado.trocaNaNota': {
      const t = v['troca'];
      if (!ehObjeto(t) || !ehUuid(t['id']) || !ehUuid(t['notaId'])) return null;
      const volta = t['volta'];
      const entra = t['entra'];
      if (!ehObjeto(volta) || !ehObjeto(entra)) return null;
      if (!ehUuid(volta['produtoId']) || !ehUuid(volta['remessaId'])) return null;
      if (!ehUuid(entra['produtoId']) || !ehUuid(entra['remessaId'])) return null;
      return {
        operacao,
        troca: {
          id: t['id'],
          notaId: t['notaId'],
          volta: { produtoId: volta['produtoId'], remessaId: volta['remessaId'] },
          entra: { produtoId: entra['produtoId'], remessaId: entra['remessaId'] },
        },
      };
    }
    case 'consignado.remarcar':
      return ehUuid(v['id']) &&
        ehUuid(v['notaId']) &&
        ehTexto(v['dataCobranca']) &&
        DIA.test(v['dataCobranca'])
        ? { operacao, id: v['id'], notaId: v['notaId'], dataCobranca: v['dataCobranca'] }
        : null;
    case 'consignado.pagamento': {
      const p = v['pagamento'];
      if (!ehObjeto(p) || !ehUuid(p['id']) || !ehUuid(p['clienteId'])) return null;
      const f = formas(p['formas']);
      const data = p['data'];
      if (data !== undefined && !(ehTexto(data) && DIA.test(data))) return null;
      return f
        ? {
            operacao,
            pagamento: {
              id: p['id'],
              clienteId: p['clienteId'],
              formas: f,
              ...(data !== undefined ? { data } : {}),
            },
          }
        : null;
    }
    case 'consignado.estornarAcerto': {
      const m = motivo(v['motivo']);
      return ehUuid(v['id']) && ehUuid(v['acertoId']) && m
        ? { operacao, id: v['id'], acertoId: v['acertoId'], motivo: m }
        : null;
    }
    case 'consignado.estornarRecibo': {
      const m = motivo(v['motivo']);
      return ehUuid(v['id']) && ehUuid(v['reciboId']) && m
        ? { operacao, id: v['id'], reciboId: v['reciboId'], motivo: m }
        : null;
    }
    case 'consignado.pesquisar':
      return textoAte(v['texto'], 100) ? { operacao, texto: v['texto'] } : null;
    case 'consignado.relatorio': {
      const r = relatorio(v['relatorio']);
      return r ? { operacao, relatorio: r } : null;
    }
    default:
      return null;
  }
}

/** O nome sugerido para o PDF do relatório (a tela só sugere; o processo principal limpa de novo). */
export function validarSalvarRelatorio(v: unknown): { nome: string } | null {
  return ehObjeto(v) && textoAte(v['nome'], 120) ? { nome: v['nome'] } : null;
}

/** O pedido de preparar o WhatsApp: telefone do cliente (ou vazio), mensagem curta e o nome sugerido do PDF. */
export function validarPrepararWhatsapp(
  v: unknown,
): { telefone: string | null; mensagem: string; nome: string; semNumero: boolean } | null {
  if (!ehObjeto(v)) return null;
  const { telefone, mensagem, nome, semNumero } = v;
  if (semNumero !== undefined && typeof semNumero !== 'boolean') return null;
  if (telefone !== null && !textoAte(telefone, 40)) return null;
  if (!textoAte(mensagem, 1000) || !textoAte(nome, 120)) return null;
  return { telefone: telefone ?? null, mensagem, nome, semNumero: semNumero === true };
}
