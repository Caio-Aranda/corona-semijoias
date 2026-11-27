import type { NoMesmoFormato } from '../idioma';
import type { PT_TEXTOS } from '../../textos';
import { TEXTOS_DE_PRODUTOS } from '../../textos-produtos';
import { TEXTOS_DE_VENDEDORES } from '../../textos-vendedores';

/** Textos de un registro básico (la palabra y el género cambian; la estructura es la misma). */
function registroBasico(c: {
  genero: 'o' | 'a';
  titulo: string;
  singular: string;
  plural: string;
  buscaRotulo: string;
}) {
  const nuevo = c.genero === 'a' ? 'Nueva' : 'Nuevo';
  const los = c.genero === 'a' ? 'las' : 'los';
  const ninguno = c.genero === 'a' ? 'Ninguna' : 'Ninguno';
  const encontrado = c.genero === 'a' ? 'encontrada' : 'encontrado';
  const inactivo = c.genero === 'a' ? 'inactiva' : 'inactivo';
  const activo = c.genero === 'a' ? 'activa' : 'activo';
  return {
    titulo: c.titulo,
    item: c.singular,
    itens: [c.singular, c.plural] as const,
    novo: `${nuevo} ${c.singular}`,
    buscando: `Buscando ${los} ${c.plural}...`,
    buscaRotulo: c.buscaRotulo,
    legenda: `Lista de ${c.plural}`,
    editar: 'Editar',
    excluir: `Eliminar ${c.singular}`,
    inativar: `Inactivar ${c.singular}`,
    reativar: `Reactivar ${c.singular}`,
    colunaAcoes: 'Qué hacer',
    vazio: `Todavía no hay ${c.genero === 'a' ? 'ninguna' : 'ningún'} ${c.singular}.`,
    semResultado: `${ninguno} ${c.singular} ${encontrado}. Revise lo que escribió o limpie la búsqueda.`,
    semInativos: `${ninguno} ${c.singular} ${inactivo}.`,
    marcaInativo: `(${inactivo})`,
    formulario: { tituloNovo: `${nuevo} ${c.singular}`, tituloEditar: `Editar ${c.singular}` },
    confirmar: {
      titulo: (nome: string) => `¿Eliminar ${nome}?`,
      texto: (nome: string) =>
        `${nome} desaparece de las listas. Lo que ya se registró con ${c.genero === 'a' ? 'ella' : 'él'} sigue guardado. Desde aquí no se puede deshacer.`,
      voltar: 'Volver',
      tituloInativar: (nome: string) => `¿Inactivar ${nome}?`,
      textoInativar: (nome: string) =>
        `${nome} deja de aparecer en las listas de elección y en las búsquedas, pero sigue en los informes y en el historial. Puede volver cuando quiera, solo hay que reactivar.`,
    },
    feito: {
      inativado: (nome: string) => `${nome} fue inactivad${c.genero}. Puede volver cuando quiera.`,
      reativado: (nome: string) => `${nome} volvió a estar ${activo}.`,
      criado: (nome: string) => `${nome} fue registrad${c.genero}.`,
      editado: (nome: string) => `Se guardaron los datos de ${nome}.`,
      excluido: (nome: string) => `${nome} fue eliminad${c.genero}.`,
    },
  };
}

export const ES_TEXTOS: NoMesmoFormato<typeof PT_TEXTOS> = {
  botoes: {
    sair: 'Salir',
    entrar: 'Entrar',
    cancelar: 'Cancelar',
  },
  saldoDaLoja: {
    acabou: 'Se acabó',
    zeroNaLoja: '0 en la tienda',
    emMaleta: (total: number, nomes: string) => `Está en maleta: ${total} (${nomes})`,
    nomes: (lista: readonly string[]): string =>
      lista.length <= 3
        ? lista.join(', ')
        : `${lista.slice(0, 3).join(', ')} y ${lista.length - 3} más`,
    semEstoqueNaMontagem: (lista: readonly string[]): string =>
      lista.length === 1
        ? `Sin stock en la tienda. Está en la maleta de ${lista[0] as string}.`
        : `Sin stock en la tienda. Está en las maletas de ${
            lista.length <= 3
              ? lista.slice(0, -1).join(', ') + ' y ' + (lista[lista.length - 1] as string)
              : `${lista.slice(0, 3).join(', ')} y ${lista.length - 3} más`
          }.`,
  },
  semInternet: {
    titulo: 'Sin internet.',
    texto: 'Corona Gestión solo funciona con internet. Conéctese e intente de nuevo.',
  },
  /** Pantallas que necesitan un registro hecho antes: lo que falta y el botón que lleva hasta allí. */
  faltaCadastro: {
    semSemana: 'Todavía no hay semana abierta. Registre la semana para armar la maleta.',
    semSemanaParaRepor: 'Todavía no hay semana abierta. Registre la semana para reponer la maleta.',
    cadastrarSemana: 'Registrar semana',
    semRepresentante:
      'Todavía no hay representante activo. Registre al representante (o active el que ya existe) para armar la maleta.',
    cadastrarRepresentante: 'Registrar representante',
    semFornecedor:
      'Todavía no hay proveedor registrado. Registre al proveedor para cargar la compra.',
    cadastrarFornecedor: 'Registrar proveedor',
    semCategoria:
      'Todavía no hay categoría registrada. Registre la categoría para registrar la pieza.',
    cadastrarCategoria: 'Registrar categoría',
  },
  /** Botón que resuelve el error más común de cada código de la API. */
  resolverErro: {
    cadastrarSemana: 'Registrar semana',
    abrirRepresentantes: 'Abrir representantes',
    lancarCotacao: 'Cargar la cotización del día',
    verEstoque: 'Ver el stock',
    receberCompra: 'Recibir compra',
  },
  semConexao: {
    texto: 'Sin conexión a internet. Intente de nuevo.',
    aviso: 'No necesita entrar de nuevo. El programa intenta solo en unos segundos.',
  },
  algoDeuErrado:
    'Algo salió mal. Intente de nuevo. Si sigue así, hable con el soporte del sistema.',
  sessaoTerminou: {
    titulo: 'Su sesión terminó.',
    texto: 'Entre de nuevo con su usuario y su contraseña para continuar.',
  },
  desatualizado: {
    titulo: 'Su programa está desactualizado.',
    texto: 'Actualice para seguir usándolo.',
  },
  atualizacao: {
    pronta: 'Hay una versión nueva. Se instalará cuando cierre el programa.',
  },
  senhaNova: {
    titulo: 'Cree su contraseña nueva.',
    texto:
      'La contraseña que recibió es temporal. Para continuar, cree una contraseña nueva que solo usted sepa.',
    regrasTitulo: 'Su contraseña nueva tiene que tener:',
    regras: [
      'por lo menos 8 caracteres;',
      'letras y números;',
      'una contraseña distinta de la temporal.',
    ],
    naoEstaBoa:
      'La contraseña nueva todavía no sirve. Necesita tener por lo menos 8 caracteres, con letras y números.',
    naoSaoIguais:
      'Las dos contraseñas no son iguales. Escriba la misma contraseña en los dos campos.',
  },
  menu: {
    inicio: 'Inicio',
    cadastros: 'Registros',
    pessoas: 'Personas',
    produtos: 'Productos',
    fornecedores: 'Proveedores',
    categorias: 'Categorías',
    pracas: 'Plazas',
    semanas: 'Semanas',
    restricoes: 'Restricciones',
    vendedores: 'Representantes',
    clientes: 'Clientes',
    usuarios: 'Usuarios',
    rotulo: 'Menú de Corona Gestión',
  },
  emBreve: {
    titulo: 'Próximamente.',
    texto: (nome: string) => `${nome} todavía se está preparando. Pronto podrá usarlo por aquí.`,
  },
  lista: {
    buscar: 'Buscar',
    limparBusca: 'Limpiar búsqueda',
    erroAoBuscar:
      'No se pudo buscar ahora. Intente de nuevo. Si sigue así, hable con el soporte del sistema.',
    atualizar: 'Actualizar',
    tentarDeNovo: 'Intentar de nuevo',
    vazio: (item: string) => `Todavía no hay ningún ${item}.`,
    semResultado: (item: string) =>
      `Ningún ${item} encontrado. Revise lo que escribió o limpie la búsqueda.`,
  },
  formulario: {
    salvar: 'Guardar',
    cancelar: 'Cancelar',
    voltar: 'Volver',
    salvando: 'Guardando...',
    alteradoPorOutraPessoa:
      'Este registro fue cambiado por otra persona. Actualice la pantalla y haga el cambio de nuevo.',
    campoNaoAceito: 'Revise este campo.',
    obrigatorio: 'Complete este campo.',
  },
  dinheiro: {
    rotulo: 'Valor en reales',
    ajuda: 'Escriba solo el valor en reales, por ejemplo 1.234,56.',
    invalido: 'Escriba un valor en reales, con máximo 2 números después de la coma.',
    cotacaoDoDia: (data: string) => `Cotización del Banco Central del ${data}.`,
    cotacaoDeDiaAnterior: (data: string) =>
      `Cotización del Banco Central del ${data}, la última publicada.`,
    cotacaoDaLoja: (data: string, hora: string) =>
      `Cotización de la tienda del ${data} a las ${hora}.`,
    semCotacao: 'Todavía no hay cotización. Los valores en Dólar y Guaraní aparecen cuando llegue.',
  },
  atencao: {
    titulo: 'Lo que necesita su atención',
    tudoEmDia: 'Todo al día.',
    erro: 'No se pudieron buscar los avisos ahora. Intente de nuevo más tarde.',
  },
  cadastros: {
    enderecoTitulo: 'Dirección',
    sim: 'Sí',
    nao: 'No',
    semVendedor: 'Ningún representante',
    vendedorAtual: 'Representante elegido antes',
    comissaoInvalida: 'Escriba la comisión de 0 a 40, por ejemplo 30 o 32,5.',
    numeroInvalido: 'Escriba solo números, sin coma ni punto.',
    situacao: {
      rotulo: 'Estado',
      coluna: 'Estado',
      ativos: 'Activos',
      inativos: 'Inactivos',
      todos: 'Todos',
      ativo: 'Activo',
      inativo: 'Inactivo',
    },
    fornecedores: {
      ...registroBasico({
        genero: 'o',
        titulo: 'Proveedores',
        singular: 'proveedor',
        plural: 'proveedores',
        buscaRotulo: 'Buscar por nombre, contacto o CNPJ',
      }),
      colunas: { nome: 'Proveedor', cnpj: 'CNPJ', contato: 'Contacto', telefone: 'Teléfono' },
      campos: {
        razaoSocial: 'Nombre del proveedor (razón social)',
        cnpj: 'CNPJ',
        cnpjAjuda: 'Puede escribirlo con o sin puntos. Si no lo sabe, déjelo en blanco.',
        contatoNome: 'Nombre del contacto',
        telefone: 'Teléfono',
        email: 'Correo electrónico',
      },
    },
    categorias: {
      ...registroBasico({
        genero: 'a',
        titulo: 'Categorías',
        singular: 'categoría',
        plural: 'categorías',
        buscaRotulo: 'Buscar por nombre',
      }),
      colunas: { nome: 'Categoría' },
      campos: { nome: 'Nombre de la categoría' },
    },
    pracas: {
      ...registroBasico({
        genero: 'a',
        titulo: 'Plazas',
        singular: 'plaza',
        plural: 'plazas',
        buscaRotulo: 'Buscar por nombre',
      }),
      colunas: { nome: 'Plaza' },
      campos: { nome: 'Nombre de la plaza' },
    },
    semanas: {
      ...registroBasico({
        genero: 'a',
        titulo: 'Semanas',
        singular: 'semana',
        plural: 'semanas',
        buscaRotulo: 'Buscar por número',
      }),
      colunas: {
        numero: 'Semana',
        inicio: 'Primer día',
        fim: 'Último día',
        situacao: 'Estado',
      },
      campos: {
        numero: 'Número de la semana',
        inicio: 'Primer día',
        fim: 'Último día',
        situacao: 'Estado',
      },
      situacao: { ABERTA: 'Abierta', FECHADA: 'Cerrada' },
      nome: (numero: string) => `Semana ${numero}`,
    },
    restricoes: {
      ...registroBasico({
        genero: 'a',
        titulo: 'Restricciones',
        singular: 'restricción',
        plural: 'restricciones',
        buscaRotulo: 'Buscar por código o por descripción',
      }),
      colunas: { codigo: 'Código', descricao: 'Descripción', bloqueia: 'Impide armar maleta' },
      campos: {
        codigo: 'Código',
        descricao: 'Descripción',
        bloqueiaRemessa: 'Impide armar maleta para el representante con esta restricción',
      },
    },
    clientes: {
      ...registroBasico({
        genero: 'o',
        titulo: 'Clientes',
        singular: 'cliente',
        plural: 'clientes',
        buscaRotulo: 'Buscar por nombre, teléfono o CPF',
      }),
      colunas: {
        nome: 'Cliente',
        documento: 'Documento',
        telefone: 'Teléfono',
        whatsapp: 'WhatsApp',
      },
      campos: {
        nome: 'Nombre del cliente',
        documentoTipo: 'Tipo de documento',
        documento: 'Número del documento',
        documentoAjuda:
          'Puede ser CPF, RG, cédula paraguaya... Si es CPF, puede escribirlo con o sin puntos. Si no lo sabe, déjelo en blanco.',
        comissaoPercentual: 'Comisión del cliente (%)',
        comissaoPercentualAjuda: 'De 0 a 40. Si no la cambia, vale 30.',
        indicadoPorNome: 'Recomendado por',
        indicadoPorNomeAjuda:
          'Nombre de quien lo recomendó. Si el cliente ya fue recomendado por otro cliente registrado, déjelo en blanco para mantenerlo.',
        comoConheceu: 'Cómo conoció la tienda',
        dataNascimento: 'Fecha de nacimiento',
        dataNascimentoAjuda:
          'Si no lo sabe, déjelo en blanco. Sirve para avisar de los cumpleaños.',
        telefone: 'Teléfono',
        whatsapp: 'WhatsApp',
        observacoes: 'Observaciones',
        vendedorResponsavelId: 'Representante responsable',
        vendedorResponsavelIdAjuda: 'Una vez elegido, el representante del cliente no cambia.',
      },
      tiposDeDocumento: [
        { valor: 'CPF', rotulo: 'CPF' },
        { valor: 'RG', rotulo: 'RG' },
        { valor: 'CI', rotulo: 'CI (cédula paraguaya)' },
        { valor: 'OUTRO', rotulo: 'Otro documento' },
      ],
    },
    endereco: {
      logradouro: 'Calle o avenida',
      numero: 'Número',
      complemento: 'Complemento',
      bairro: 'Barrio',
      cidade: 'Ciudad',
      uf: 'Departamento',
      cep: 'Código postal',
    },
  },
  usuarios: {
    titulo: 'Usuarios',
    item: 'usuario',
    itens: ['usuario', 'usuarios'] as const,
    novo: 'Nuevo usuario',
    buscando: 'Buscando los usuarios...',
    buscaRotulo: 'Buscar por nombre o usuario',
    filtroAcesso: 'Tipo de acceso',
    filtroSituacao: 'Estado',
    todos: 'Todos',
    ativos: 'Activos',
    desativados: 'Inactivos',
    colunas: {
      nome: 'Nombre',
      usuario: 'Usuario',
      acesso: 'Tipo de acceso',
      situacao: 'Estado',
      acoes: 'Qué hacer',
    },
    acesso: { ADMIN: 'Dueña', VENDEDOR: 'Representante' },
    situacao: { ativo: 'Activo', desativado: 'Inactivo' },
    legenda: 'Usuarios que pueden entrar en los programas',
    editar: 'Editar',
    desativar: 'Inactivar usuario',
    reativar: 'Reactivar usuario',
    novaSenha: 'Nueva contraseña temporal',
    criarSenha: 'Crear contraseña temporal',
    formulario: {
      tituloNovo: 'Nuevo usuario',
      tituloEditar: 'Editar usuario',
      nome: 'Nombre',
      usuario: 'Usuario',
      usuarioAjuda: 'De 3 a 40 letras sin tilde, números o punto. Por ejemplo: maria.silva.',
      acesso: 'Tipo de acceso',
      vendedor: 'Representante',
      escolherVendedor: 'Elija el representante',
      escolhaOVendedor: 'Elija el representante de este acceso.',
      vendedorAtual: 'Representante ya vinculado a este acceso',
      semVendedores: 'No hay representante activo registrado. Registre el representante antes.',
      salvar: 'Guardar',
    },
    senha: {
      titulo: (nome: string) => `Contraseña temporal de ${nome}`,
      aviso: 'Anote esta contraseña ahora. No aparece de nuevo.',
      entregar: (nome: string) =>
        `Entregue la contraseña solo a ${nome}. En el primer acceso, la persona crea una contraseña nueva.`,
      validade: (data: string) => `La contraseña vale hasta el ${data}.`,
      copiar: 'Copiar contraseña',
      copiada: 'Contraseña copiada.',
      naoCopiou: 'No se pudo copiar. Anote la contraseña a mano.',
      fechar: 'Ya anoté, cerrar',
    },
    confirmar: {
      desativarTitulo: (nome: string) => `¿Inactivar a ${nome}?`,
      desativarTexto: (nome: string) =>
        `${nome} sale del sistema ahora y ya no va a poder entrar. Usted puede reactivar después.`,
      reativarTitulo: (nome: string) => `¿Reactivar a ${nome}?`,
      reativarTexto: (nome: string) =>
        `${nome} vuelve a poder entrar con la contraseña que ya tenía. Quien estaba adentro tiene que entrar de nuevo.`,
      novaSenhaTitulo: (nome: string) => `¿Crear una nueva contraseña temporal para ${nome}?`,
      novaSenhaTexto: (nome: string) =>
        `La contraseña actual de ${nome} deja de valer y quien esté adentro sale del sistema ahora. Se creará una contraseña temporal nueva.`,
      voltar: 'Volver',
    },
    feito: {
      criado: (nome: string) => `${nome} fue registrado.`,
      editado: (nome: string) => `Se guardaron los datos de ${nome}.`,
      desativado: (nome: string) => `${nome} fue inactivado y salió del sistema.`,
      reativado: (nome: string) => `${nome} fue reactivado.`,
    },
  },
  vendedores: TEXTOS_DE_VENDEDORES,
  produtos: TEXTOS_DE_PRODUTOS,
};
