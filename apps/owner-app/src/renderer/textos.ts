import { TEXTOS_DE_PRODUTOS } from './textos-produtos';
import { TEXTOS_DE_VENDEDORES } from './textos-vendedores';

/**
 * Textos fixos que o servidor não consegue montar (por exemplo, quando nem chegamos a falar com ele).
 * Vêm do glossário da interface (docs/glossario-interface.md, partes 4 e 6).
 * Mensagens de erro da API nunca são escritas aqui: a tela mostra o que o servidor devolveu.
 */

/** Textos de um cadastro básico (a palavra e o gênero mudam; a estrutura é a mesma). */
function cadastroBasico(c: {
  genero: 'o' | 'a';
  titulo: string;
  singular: string;
  plural: string;
  buscaRotulo: string;
}) {
  const novo = c.genero === 'a' ? 'Nova' : 'Novo';
  return {
    titulo: c.titulo,
    item: c.singular,
    itens: [c.singular, c.plural] as const,
    novo: `${novo} ${c.singular}`,
    buscando: `Buscando ${c.genero === 'a' ? 'as' : 'os'} ${c.plural}...`,
    buscaRotulo: c.buscaRotulo,
    legenda: `Lista de ${c.plural}`,
    editar: 'Editar',
    excluir: `Excluir ${c.singular}`,
    inativar: `Inativar ${c.singular}`,
    reativar: `Reativar ${c.singular}`,
    colunaAcoes: 'O que fazer',
    vazio: `Ainda não há ${c.genero === 'a' ? 'nenhuma' : 'nenhum'} ${c.singular}.`,
    semResultado: `${c.genero === 'a' ? 'Nenhuma' : 'Nenhum'} ${c.singular} encontrad${c.genero}. Confira o que você digitou ou limpe a busca.`,
    /** Lista sem ninguém na situação Inativos. */
    semInativos: `${c.genero === 'a' ? 'Nenhuma' : 'Nenhum'} ${c.singular} inativ${c.genero}.`,
    /** Marca de quem está inativo nas listas de escolha. */
    marcaInativo: `(inativ${c.genero})`,
    formulario: { tituloNovo: `${novo} ${c.singular}`, tituloEditar: `Editar ${c.singular}` },
    confirmar: {
      titulo: (nome: string) => `Excluir ${nome}?`,
      texto: (nome: string) =>
        `${nome} some das listas. O que já foi registrado com ${c.genero === 'a' ? 'ela' : 'ele'} continua guardado. Por aqui não dá para desfazer.`,
      voltar: 'Voltar',
      /** Inativar não é excluir: some das escolhas, fica nos relatórios e pode voltar. */
      tituloInativar: (nome: string) => `Inativar ${nome}?`,
      textoInativar: (nome: string) =>
        `${nome} deixa de aparecer nas listas de escolha e nas buscas, mas continua nos relatórios e no histórico. Pode voltar a qualquer hora, é só reativar.`,
    },
    feito: {
      inativado: (nome: string) => `${nome} foi inativad${c.genero}. Pode voltar a qualquer hora.`,
      reativado: (nome: string) => `${nome} voltou a ficar ativ${c.genero}.`,
      criado: (nome: string) => `${nome} foi cadastrad${c.genero}.`,
      editado: (nome: string) => `Os dados de ${nome} foram salvos.`,
      excluido: (nome: string) => `${nome} foi exclu${c.genero === 'a' ? 'ída' : 'ído'}.`,
    },
  };
}

export const TEXTOS = {
  semInternet: {
    titulo: 'Sem internet.',
    texto: 'O Corona Gestão só funciona com internet. Conecte-se e tente de novo.',
  },
  /** Pessoa já dentro do aplicativo e a internet caiu: a sessão fica guardada e o app tenta de novo. */
  semConexao: {
    texto: 'Sem conexão com a internet. Tente de novo.',
    aviso: 'Você não precisa entrar de novo. O aplicativo tenta sozinho em alguns segundos.',
  },
  algoDeuErrado:
    'Algo deu errado agora. Tente de novo. Se continuar, fale com o suporte do sistema.',
  sessaoTerminou: {
    titulo: 'Sua sessão terminou.',
    texto: 'Entre de novo com seu usuário e sua senha para continuar.',
  },
  desatualizado: {
    titulo: 'Seu aplicativo está desatualizado.',
    texto: 'Atualize para continuar usando.',
  },
  senhaNova: {
    titulo: 'Crie sua senha nova.',
    texto:
      'A senha que você recebeu é temporária. Para continuar, crie uma senha nova que só você saiba.',
    regrasTitulo: 'Sua senha nova precisa ter:',
    regras: ['pelo menos 8 caracteres;', 'letras e números;', 'uma senha diferente da temporária.'],
    naoEstaBoa:
      'A senha nova ainda não está boa. Ela precisa ter pelo menos 8 caracteres, com letras e números.',
    naoSaoIguais: 'As duas senhas não são iguais. Digite a mesma senha nos dois campos.',
  },
  menu: {
    inicio: 'Início',
    cadastros: 'Cadastros',
    pessoas: 'Pessoas',
    produtos: 'Produtos',
    fornecedores: 'Fornecedores',
    categorias: 'Categorias',
    pracas: 'Praças',
    semanas: 'Semanas',
    restricoes: 'Restrições',
    vendedores: 'Vendedores',
    clientes: 'Clientes',
    usuarios: 'Usuários',
    rotulo: 'Menu do Corona Gestão',
  },
  emBreve: {
    titulo: 'Em breve.',
    texto: (nome: string) => `${nome} ainda está sendo preparado. Logo você poderá usar por aqui.`,
  },
  lista: {
    buscar: 'Buscar',
    limparBusca: 'Limpar busca',
    erroAoBuscar:
      'Não foi possível buscar agora. Tente de novo. Se continuar, fale com o suporte do sistema.',
    atualizar: 'Atualizar',
    tentarDeNovo: 'Tentar de novo',
    vazio: (item: string) => `Ainda não há nenhum ${item}.`,
    semResultado: (item: string) =>
      `Nenhum ${item} encontrado. Confira o que você digitou ou limpe a busca.`,
  },
  formulario: {
    salvar: 'Salvar',
    cancelar: 'Cancelar',
    voltar: 'Voltar',
    salvando: 'Salvando...',
    alteradoPorOutraPessoa:
      'Este cadastro foi alterado por outra pessoa. Atualize a tela e faça a alteração de novo.',
    campoNaoAceito: 'Confira este campo.',
    obrigatorio: 'Preencha este campo.',
  },
  dinheiro: {
    rotulo: 'Valor em reais',
    ajuda: 'Digite só o valor em reais, por exemplo 1.234,56.',
    invalido: 'Digite um valor em reais, com no máximo 2 números depois da vírgula.',
    cotacaoDoDia: (data: string) => `Cotação do Banco Central de ${data}.`,
    cotacaoDeDiaAnterior: (data: string) =>
      `Cotação do Banco Central de ${data}, a última publicada.`,
    semCotacao: 'Ainda não há cotação. Os valores em Dólar e Guarani aparecem quando ela chegar.',
  },
  atencao: {
    titulo: 'O que precisa da sua atenção',
    tudoEmDia: 'Tudo em dia.',
    erro: 'Não foi possível buscar os avisos agora. Tente de novo mais tarde.',
  },
  cadastros: {
    enderecoTitulo: 'Endereço',
    sim: 'Sim',
    nao: 'Não',
    semVendedor: 'Nenhum vendedor',
    vendedorAtual: 'Vendedor já escolhido antes',
    numeroInvalido: 'Digite só números, sem vírgula nem ponto.',
    /** Filtro e coluna de situação dos cadastros que podem ser inativados. */
    situacao: {
      rotulo: 'Situação',
      coluna: 'Situação',
      ativos: 'Ativos',
      inativos: 'Inativos',
      todos: 'Todos',
      ativo: 'Ativo',
      inativo: 'Inativo',
    },
    fornecedores: {
      ...cadastroBasico({
        genero: 'o',
        titulo: 'Fornecedores',
        singular: 'fornecedor',
        plural: 'fornecedores',
        buscaRotulo: 'Buscar por nome, contato ou CNPJ',
      }),
      colunas: { nome: 'Fornecedor', cnpj: 'CNPJ', contato: 'Contato', telefone: 'Telefone' },
      campos: {
        razaoSocial: 'Nome do fornecedor (razão social)',
        cnpj: 'CNPJ',
        cnpjAjuda: 'Pode digitar com ou sem pontos. Se não souber, deixe em branco.',
        contatoNome: 'Nome do contato',
        telefone: 'Telefone',
        email: 'E-mail',
      },
    },
    categorias: {
      ...cadastroBasico({
        genero: 'a',
        titulo: 'Categorias',
        singular: 'categoria',
        plural: 'categorias',
        buscaRotulo: 'Buscar pelo nome',
      }),
      colunas: { nome: 'Categoria' },
      campos: { nome: 'Nome da categoria' },
    },
    pracas: {
      ...cadastroBasico({
        genero: 'a',
        titulo: 'Praças',
        singular: 'praça',
        plural: 'praças',
        buscaRotulo: 'Buscar pelo nome',
      }),
      colunas: { nome: 'Praça' },
      campos: { nome: 'Nome da praça' },
    },
    semanas: {
      ...cadastroBasico({
        genero: 'a',
        titulo: 'Semanas',
        singular: 'semana',
        plural: 'semanas',
        buscaRotulo: 'Buscar pelo número',
      }),
      colunas: {
        numero: 'Semana',
        inicio: 'Primeiro dia',
        fim: 'Último dia',
        situacao: 'Situação',
      },
      campos: {
        numero: 'Número da semana',
        inicio: 'Primeiro dia',
        fim: 'Último dia',
        situacao: 'Situação',
      },
      situacao: { ABERTA: 'Aberta', FECHADA: 'Fechada' } as Record<string, string>,
      nome: (numero: string) => `Semana ${numero}`,
    },
    restricoes: {
      ...cadastroBasico({
        genero: 'a',
        titulo: 'Restrições',
        singular: 'restrição',
        plural: 'restrições',
        buscaRotulo: 'Buscar pelo código ou pela descrição',
      }),
      colunas: { codigo: 'Código', descricao: 'Descrição', bloqueia: 'Impede montar maleta' },
      campos: {
        codigo: 'Código',
        descricao: 'Descrição',
        bloqueiaRemessa: 'Impede montar maleta para o vendedor com esta restrição',
      },
    },
    clientes: {
      ...cadastroBasico({
        genero: 'o',
        titulo: 'Clientes',
        singular: 'cliente',
        plural: 'clientes',
        buscaRotulo: 'Buscar por nome, telefone ou CPF',
      }),
      colunas: { nome: 'Cliente', cpf: 'CPF', telefone: 'Telefone', whatsapp: 'WhatsApp' },
      campos: {
        nome: 'Nome do cliente',
        cpf: 'CPF',
        cpfAjuda: 'Pode digitar com ou sem pontos. Se não souber, deixe em branco.',
        dataNascimento: 'Data de nascimento',
        dataNascimentoAjuda:
          'Se não souber, deixe em branco. Serve para avisar dos aniversariantes.',
        telefone: 'Telefone',
        whatsapp: 'WhatsApp',
        observacoes: 'Observações',
        vendedorResponsavelId: 'Vendedor responsável',
      },
    },
    endereco: {
      logradouro: 'Rua ou avenida',
      numero: 'Número',
      complemento: 'Complemento',
      bairro: 'Bairro',
      cidade: 'Cidade',
      uf: 'Estado',
      cep: 'CEP',
    },
  },
  usuarios: {
    titulo: 'Usuários',
    item: 'usuário',
    itens: ['usuário', 'usuários'] as const,
    novo: 'Novo usuário',
    buscando: 'Buscando os usuários...',
    buscaRotulo: 'Buscar por nome ou usuário',
    filtroAcesso: 'Tipo de acesso',
    filtroSituacao: 'Situação',
    todos: 'Todos',
    ativos: 'Ativos',
    desativados: 'Desativados',
    colunas: {
      nome: 'Nome',
      usuario: 'Usuário',
      acesso: 'Tipo de acesso',
      situacao: 'Situação',
      acoes: 'O que fazer',
    },
    acesso: { ADMIN: 'Dona', VENDEDOR: 'Vendedor' } as Record<string, string>,
    situacao: { ativo: 'Ativo', desativado: 'Desativado' },
    legenda: 'Usuários que podem entrar nos aplicativos',
    editar: 'Editar',
    desativar: 'Desativar usuário',
    reativar: 'Reativar usuário',
    novaSenha: 'Nova senha temporária',
    criarSenha: 'Criar senha temporária',
    formulario: {
      tituloNovo: 'Novo usuário',
      tituloEditar: 'Editar usuário',
      nome: 'Nome',
      usuario: 'Usuário',
      usuarioAjuda: 'De 3 a 40 letras sem acento, números ou ponto. Por exemplo: maria.silva.',
      acesso: 'Tipo de acesso',
      vendedor: 'Vendedor',
      escolherVendedor: 'Escolha o vendedor',
      escolhaOVendedor: 'Escolha o vendedor deste acesso.',
      vendedorAtual: 'Vendedor já ligado a este acesso',
      semVendedores: 'Não há vendedor ativo cadastrado. Cadastre o vendedor antes.',
      salvar: 'Salvar',
    },
    senha: {
      titulo: (nome: string) => `Senha temporária de ${nome}`,
      aviso: 'Anote esta senha agora. Ela não aparece de novo.',
      entregar: (nome: string) =>
        `Entregue a senha só para ${nome}. No primeiro acesso, a pessoa cria uma senha nova.`,
      validade: (data: string) => `A senha vale até ${data}.`,
      copiar: 'Copiar senha',
      copiada: 'Senha copiada.',
      naoCopiou: 'Não foi possível copiar. Anote a senha à mão.',
      fechar: 'Já anotei, fechar',
    },
    confirmar: {
      desativarTitulo: (nome: string) => `Desativar ${nome}?`,
      desativarTexto: (nome: string) =>
        `${nome} vai sair do sistema agora e não vai mais conseguir entrar. Você pode reativar depois.`,
      reativarTitulo: (nome: string) => `Reativar ${nome}?`,
      reativarTexto: (nome: string) =>
        `${nome} volta a poder entrar com a senha que já tinha. Quem estava dentro precisa entrar de novo.`,
      novaSenhaTitulo: (nome: string) => `Criar nova senha temporária para ${nome}?`,
      novaSenhaTexto: (nome: string) =>
        `A senha atual de ${nome} deixa de valer e quem estiver dentro sai do sistema agora. Será criada uma senha temporária nova.`,
      voltar: 'Voltar',
    },
    feito: {
      criado: (nome: string) => `${nome} foi cadastrado.`,
      editado: (nome: string) => `Os dados de ${nome} foram salvos.`,
      desativado: (nome: string) => `${nome} foi desativado e saiu do sistema.`,
      reativado: (nome: string) => `${nome} foi reativado.`,
    },
  },
  vendedores: TEXTOS_DE_VENDEDORES,
  produtos: TEXTOS_DE_PRODUTOS,
} as const;
