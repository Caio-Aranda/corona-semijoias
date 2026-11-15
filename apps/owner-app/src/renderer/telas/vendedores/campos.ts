import {
  ABAS_DO_VENDEDOR,
  CAMPOS_DA_REFERENCIA,
  CAMPOS_DAS_ABAS,
  CAMPOS_DO_VENDEDOR,
  type AbaDoVendedor,
} from '../../../shared/vendedores';
import { TEXTOS } from '../../textos';
import { daApi, type CampoDaFicha } from './dados';

const T = TEXTOS.vendedores;

/** Ajustes da tela sobre os campos que a API conhece (obrigatório, listas, ajudas). */
const AJUSTES: Record<string, Partial<CampoDaFicha>> = {
  nome: { obrigatorio: true },
  sexo: { opcoes: T.sexo, semOpcao: T.semSexo },
  pracaId: { lista: 'pracas', semOpcao: T.semPraca },
  restricaoId: { lista: 'restricoes', semOpcao: T.semRestricao },
  limiteConsignacao: {
    ajuda: T.limiteAjuda,
    obrigatorio: true,
    obrigatorioExcetoSe: 'semLimite',
    mensagemObrigatorio: T.limiteObrigatorio,
    desabilitaSe: 'semLimite',
  },
};

function comAjustes(campos: readonly CampoDaFicha[]): CampoDaFicha[] {
  return campos.map((c) => ({ ...c, ...(AJUSTES[c.nome] ?? {}) }));
}

/** Aba "Cadastro": na ordem em que a pessoa preenche. */
const ORDEM_DO_CADASTRO = [
  'nome',
  'dataNascimento',
  'sexo',
  'estrangeiro',
  'cpfCnpj',
  'rgIe',
  'rgUf',
  'naturalidade',
  'escolaridade',
  'telefone1',
  'telefone2',
  'whatsapp',
  'email',
  'facebook',
  'outrasMidias',
  'pracaId',
  'limiteConsignacao',
  'semLimite',
  'restricaoId',
];

export const CAMPOS_DO_CADASTRO: CampoDaFicha[] = comAjustes(daApi(CAMPOS_DO_VENDEDOR)).sort(
  (a, b) => ORDEM_DO_CADASTRO.indexOf(a.nome) - ORDEM_DO_CADASTRO.indexOf(b.nome),
);

/** Nomes de campo que se repetem em abas diferentes e precisam de um rótulo próprio. */
const ROTULOS_DAS_ABAS: Partial<Record<AbaDoVendedor, Record<string, string>>> = {
  trabalho: { telefone: 'Telefone do trabalho' },
  conjuge: {
    nome: 'Nome do cônjuge',
    telefone: 'Telefone do cônjuge',
    empresa: 'Empresa do cônjuge',
    renda: 'Renda do cônjuge',
    dataNascimento: 'Data de nascimento do cônjuge',
  },
};

export const CAMPOS_DA_TELA_DAS_ABAS: Record<AbaDoVendedor, CampoDaFicha[]> = Object.fromEntries(
  ABAS_DO_VENDEDOR.map((aba) => [
    aba,
    aba === 'observacoes'
      ? [{ nome: 'texto', tipo: 'area', max: 4000 } satisfies CampoDaFicha]
      : daApi(CAMPOS_DAS_ABAS[aba]).map((c) => {
          const rotulo = ROTULOS_DAS_ABAS[aba]?.[c.nome];
          return rotulo ? { ...c, rotulo } : c;
        }),
  ]),
) as Record<AbaDoVendedor, CampoDaFicha[]>;

export const CAMPOS_DA_TELA_DA_REFERENCIA: CampoDaFicha[] = daApi(CAMPOS_DA_REFERENCIA).map((c) =>
  c.nome === 'nome'
    ? { ...c, obrigatorio: true, rotulo: 'Nome da pessoa' }
    : c.nome === 'observacao'
      ? { ...c, tipo: 'area' }
      : c,
);

/** As abas da ficha, na ordem em que aparecem. */
export const ABAS_DA_FICHA = [
  'cadastro',
  'endereco',
  'contato',
  'trabalho',
  'conjuge',
  'mei',
  'referencias',
  'observacoes',
  'documentos',
] as const;
export type ChaveDaAba = (typeof ABAS_DA_FICHA)[number];
