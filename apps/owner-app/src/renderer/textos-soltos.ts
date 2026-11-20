import { textosVivos } from './idioma/idioma';
import { ES_TEXTOS_SOLTOS } from './idioma/es/soltos';

/** Textos que ficavam soltos nas telas e nas regras: entrada, conferências, planilhas, arquivos exportados. */
export const PT_TEXTOS_SOLTOS = {
  entrada: {
    nomeDoApp: 'Corona Gestão',
    usuario: 'Usuário',
    senha: 'Senha',
    senhaNova: 'Senha nova',
    repitaSenha: 'Repita a senha nova',
    salvarSenha: 'Salvar senha nova',
  },
  codigos: {
    longo: 'O código é longo demais. Use até 60 letras ou números.',
    naoConfere: (codigo: string) =>
      `O código de barras ${codigo} não confere. Leia o código de novo ou confira os números.`,
    incompleto: 'Este código de barras parece incompleto. Leia o código de novo.',
    semCodigo: 'Informe pelo menos um código para a peça.',
    repetido: (codigo: string) => `O código ${codigo} aparece mais de uma vez nesta peça.`,
    doisPrincipais: 'Escolha apenas um código principal.',
  },
  estoque: {
    rotulos: {
      ENTRADA: 'Entrada',
      SAIDA: 'Saída',
      AVARIA: 'Perda',
      AJUSTE: 'Ajuste',
      REMESSA: 'Maleta',
      DEVOLUCAO: 'Devolução da maleta',
    } as Record<string, string>,
    outro: 'Outro lançamento',
    mensagens: {
      quantidade: 'Digite quantas peças, com número inteiro maior que zero.',
      quantidadeGrande: 'Esse número é grande demais. Confira a quantidade.',
      contei: 'Digite quantas peças você contou. Pode ser zero.',
      contouIgual: 'Essa é a quantidade que já está no estoque. Digite a que você contou.',
      diferenca: 'Digite quantas peças a mais (3) ou a menos (-3). Não pode ser zero.',
      diferencaPassa: (saldo: number) =>
        `Não dá para tirar tantas peças: há só ${saldo} no estoque. Confira a diferença.`,
      motivoDoAjuste: 'Diga o motivo do ajuste. Isso ajuda a entender a mudança depois.',
      motivoDeDesfazer: 'Diga o motivo para desfazer. Ele fica guardado no histórico.',
      minimo: 'Digite o estoque mínimo com número inteiro. Pode ser zero.',
    },
  },
  planilha: {
    vazia: 'A planilha está vazia.',
    semLinhasDeProdutos: 'A planilha não tem nenhuma linha de produtos.',
    semLinhasDePecas: 'A planilha não tem nenhuma linha de peças.',
    colunas: {
      codigo: 'código',
      nome: 'nome',
      categoria: 'categoria',
      custo: 'custo',
      preco: 'preço',
    } as Record<string, string>,
    faltamColunas: (faltam: string) =>
      `Faltam colunas na primeira linha da planilha: ${faltam}. Use os títulos código, nome, categoria, custo e preço.`,
    linhasDemais: (max: number) =>
      `A planilha tem linhas demais. Envie até ${max} produtos por vez.`,
    faltaCodigo: 'Falta o código.',
    codigoLongo: 'O código é longo demais (até 60 letras ou números).',
    codigoRepetido: (codigo: string, linha: number) =>
      `O código ${codigo} já aparece na linha ${linha}.`,
    faltaNome: 'Falta o nome.',
    nomeLongo: 'O nome é longo demais (até 200 letras).',
    tamTonLongo: 'O Tam./Ton. é longo demais (até 30 letras).',
    faltaCategoria: 'Falta a categoria.',
    categoriaNaoExiste: (categoria: string) =>
      `A categoria "${categoria}" não existe. Cadastre a categoria antes.`,
    custoInvalido:
      'O custo não é um valor válido. Use números, com até duas casas depois da vírgula.',
    precoInvalido:
      'O preço não é um valor válido. Use números, com até duas casas depois da vírgula.',
    referenciaDaLinha: (linha: number, codigo: string) =>
      `Linha ${linha} (${codigo || 'sem código'})`,
    colunasDaCompra: {
      codigo: 'código',
      quantidade: 'quantidade',
      custo: 'custo',
    } as Record<string, string>,
  },
  arquivo: {
    pagina: (n: number, total: number) => `Página ${n} de ${total}`,
    geradoEm: (quando: string, por: string) => `Gerado em ${quando} por ${por}`,
    as: 'às',
    filtros: (lista: string) => `Filtros: ${lista}`,
    nenhum: 'nenhum',
    ativo: 'Ativo',
    inativo: 'Inativo',
    sim: 'Sim',
    nao: 'Não',
    situacao: 'Situação',
    dataDaCotacao: 'Data da cotação',
    cotacao: 'Cotação',
    planilha: 'Relatório',
    dona: 'Dona',
    periodo: (de: string, ate: string) => `Período: ${de} a ${ate}`,
    comeco: 'o começo',
    hoje: 'hoje',
    nota: { nova: 'Nova', reposicao: 'Reposição' },
  },
  painelArquivo: {
    totalEnviado: 'Total enviado',
    totalVendidoLiquido: 'Total vendido líquido',
    totalLiquido: 'Total líquido',
    cotacao: 'Dólar e Guarani pela cotação do dia de cada lançamento.',
    pecasEnviadas: 'Peças enviadas',
    pecasDevolvidas: 'Peças devolvidas',
    pecasVendidas: 'Peças vendidas',
    vendidoLiquido: 'Vendido líquido',
  },
  vendedores: {
    telefoneDoTrabalho: 'Telefone do trabalho',
    conjuge: {
      nome: 'Nome do cônjuge',
      telefone: 'Telefone do cônjuge',
      empresa: 'Empresa do cônjuge',
      renda: 'Renda do cônjuge',
      dataNascimento: 'Data de nascimento do cônjuge',
    },
    nomeDaPessoa: 'Nome da pessoa',
  },
};

export const TEXTOS_SOLTOS = textosVivos('soltos', PT_TEXTOS_SOLTOS, ES_TEXTOS_SOLTOS);
