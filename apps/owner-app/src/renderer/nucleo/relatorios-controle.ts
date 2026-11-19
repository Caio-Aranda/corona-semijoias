/**
 * Os três relatórios de Controle da dona (tarefa 8.9; glossário "Relatórios de controle"): nome na tela, filtros, colunas e
 * totais. A tela e a exportação usam esta mesma definição. Os dados vêm da API da tarefa 8.9; o texto da loja (ação, antes e depois,
 * situação, resultado) já chega pronto. Nenhum dinheiro, custo, senha ou token aparece aqui.
 */
import type { NomeDoRelatorio, NomeDoRelatorioDeControle } from '../../shared/relatorios';
import { TEXTOS_DE_RELATORIOS_DE_CONTROLE } from '../textos-relatorios-controle';
import type { DefinicaoDeRelatorio } from './relatorios';
import { idiomaAtual } from '../idioma/idioma';
import { traducirDefinicion } from '../idioma/es/relatorios-mapa';

const T = TEXTOS_DE_RELATORIOS_DE_CONTROLE;

export const RELATORIOS_DE_CONTROLE: Record<NomeDoRelatorioDeControle, DefinicaoDeRelatorio> = {
  'controle-auditoria': {
    nome: 'controle-auditoria',
    grupo: 'controle',
    titulo: T.auditoria.titulo,
    explicacao: T.auditoria.explicacao,
    nota: T.auditoria.nota,
    filtros: ['busca', 'periodo', 'usuario'],
    buscaRotulo: 'Buscar por pessoa ou tipo de registro',
    colunas: [
      { chave: 'ocorridaEm', titulo: 'Quando', tipo: 'dataHora' },
      { chave: 'quem', titulo: 'Quem fez', tipo: 'texto' },
      { chave: 'oQueFoi', titulo: 'O que foi', tipo: 'texto' },
      { chave: 'area', titulo: 'Registro', tipo: 'texto' },
      { chave: 'antes', titulo: 'Antes', tipo: 'texto' },
      { chave: 'depois', titulo: 'Depois', tipo: 'texto' },
    ],
    totaisDeValor: {},
    itens: { um: 'registro', varios: 'registros' },
    vazio: 'Nenhum registro com estes filtros.',
  },
  'controle-computadores': {
    nome: 'controle-computadores',
    grupo: 'controle',
    titulo: T.computadores.titulo,
    explicacao: T.computadores.explicacao,
    nota: T.computadores.nota,
    filtros: ['busca', 'vendedor', 'situacao', 'periodo'],
    situacoes: [
      { valor: '', rotulo: T.computadores.situacoes.TODAS },
      { valor: 'ATIVO', rotulo: T.computadores.situacoes.ATIVO },
      { valor: 'AGUARDANDO_LIBERACAO', rotulo: T.computadores.situacoes.AGUARDANDO_LIBERACAO },
      { valor: 'SUBSTITUIDO', rotulo: T.computadores.situacoes.SUBSTITUIDO },
      { valor: 'BLOQUEADO', rotulo: T.computadores.situacoes.BLOQUEADO },
    ],
    extras: [
      {
        chave: 'comProblema',
        rotulo: 'Envios com problema',
        tipo: 'escolha',
        qualquer: 'Com e sem problema',
        opcoes: [
          { valor: 'SIM', rotulo: 'Só com problema' },
          { valor: 'NAO', rotulo: 'Só sem problema' },
        ],
      },
    ],
    buscaRotulo: 'Buscar por representante',
    colunas: [
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
      { chave: 'versaoApp', titulo: 'Versão do programa', tipo: 'texto' },
      { chave: 'ultimoRecebimentoEm', titulo: 'Último recebimento', tipo: 'dataHora' },
      { chave: 'ultimoEnvioEm', titulo: 'Último envio', tipo: 'dataHora' },
      { chave: 'aguardandoEnvio', titulo: 'Esperando envio', tipo: 'inteiro' },
      { chave: 'enviosComProblema', titulo: 'Envios com problema', tipo: 'inteiro' },
      { chave: 'emQuarentena', titulo: 'Guardados à parte', tipo: 'inteiro' },
    ],
    totaisDeValor: {},
    itens: { um: 'computador', varios: 'computadores' },
    vazio: 'Nenhum computador com estes filtros.',
  },
  'controle-acessos': {
    nome: 'controle-acessos',
    grupo: 'controle',
    titulo: T.acessos.titulo,
    explicacao: T.acessos.explicacao,
    nota: T.acessos.nota,
    filtros: ['busca', 'periodo'],
    extras: [
      {
        chave: 'resultado',
        rotulo: 'Resultado',
        tipo: 'escolha',
        qualquer: 'Todos os resultados',
        opcoes: Object.entries(T.acessos.resultados).map(([valor, rotulo]) => ({ valor, rotulo })),
      },
      {
        chave: 'aplicativo',
        rotulo: 'Programa',
        tipo: 'escolha',
        qualquer: 'Os dois programas',
        opcoes: [
          { valor: 'gestao', rotulo: 'Só o programa da dona' },
          { valor: 'vendas', rotulo: 'Só o programa do representante' },
        ],
      },
    ],
    buscaRotulo: 'Buscar por usuário ou nome',
    colunas: [
      { chave: 'ocorridaEm', titulo: 'Quando', tipo: 'dataHora' },
      { chave: 'pessoaNaTela', titulo: 'Pessoa', tipo: 'texto' },
      { chave: 'login', titulo: 'Usuário digitado', tipo: 'texto' },
      { chave: 'aplicativo', titulo: 'Programa', tipo: 'texto' },
      { chave: 'resultadoNaTela', titulo: 'Resultado', tipo: 'texto' },
      { chave: 'ip', titulo: 'Endereço de internet', tipo: 'texto' },
      { chave: 'bloqueadoAte', titulo: 'Bloqueado até', tipo: 'dataHora' },
    ],
    totaisDeValor: {},
    itens: { um: 'tentativa', varios: 'tentativas' },
    vazio: 'Nenhuma tentativa com estes filtros.',
  },
};

export const ORDEM_DOS_RELATORIOS_DE_CONTROLE: NomeDoRelatorio[] = [
  'controle-auditoria',
  'controle-computadores',
  'controle-acessos',
];

/** Acrescenta às linhas o texto da tela que a rota não manda. */
export function prepararLinhaDeControle(
  nome: NomeDoRelatorio,
  linha: Record<string, unknown>,
): Record<string, unknown> {
  if (nome === 'controle-acessos') {
    return {
      ...linha,
      pessoaNaTela: typeof linha.pessoa === 'string' ? linha.pessoa : T.acessos.semPessoa,
    };
  }
  return linha;
}

// Em espanhol, os textos de cada definição saem da tabela de tradução (feita uma vez, ao abrir o programa).
if (idiomaAtual() === 'es') traducirDefinicion(RELATORIOS_DE_CONTROLE);
