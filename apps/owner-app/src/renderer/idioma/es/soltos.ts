import type { NoMesmoFormato } from '../idioma';
import type { PT_TEXTOS_SOLTOS } from '../../textos-soltos';

export const ES_TEXTOS_SOLTOS: NoMesmoFormato<typeof PT_TEXTOS_SOLTOS> = {
  entrada: {
    nomeDoApp: 'Corona Gestión',
    usuario: 'Usuario',
    senha: 'Contraseña',
    senhaNova: 'Contraseña nueva',
    repitaSenha: 'Repita la contraseña nueva',
    salvarSenha: 'Guardar contraseña nueva',
  },
  codigos: {
    longo: 'El código es demasiado largo. Use hasta 60 letras o números.',
    naoConfere: (codigo: string) =>
      `El código de barras ${codigo} no coincide. Lea el código de nuevo o revise los números.`,
    incompleto: 'Este código de barras parece incompleto. Lea el código de nuevo.',
    semCodigo: 'Informe por lo menos un código para la pieza.',
    repetido: (codigo: string) => `El código ${codigo} aparece más de una vez en esta pieza.`,
    doisPrincipais: 'Elija solo un código principal.',
  },
  estoque: {
    rotulos: {
      ENTRADA: 'Entrada',
      SAIDA: 'Salida',
      AVARIA: 'Pérdida',
      AJUSTE: 'Ajuste',
      REMESSA: 'Maleta',
      DEVOLUCAO: 'Devolución de la maleta',
    },
    outro: 'Otro movimiento',
    mensagens: {
      quantidade: 'Escriba cuántas piezas, con un número entero mayor que cero.',
      quantidadeGrande: 'Ese número es demasiado grande. Revise la cantidad.',
      contei: 'Escriba cuántas piezas contó. Puede ser cero.',
      contouIgual: 'Esa es la cantidad que ya está en el stock. Escriba la que usted contó.',
      diferenca: 'Escriba cuántas piezas de más (3) o de menos (-3). No puede ser cero.',
      diferencaPassa: (saldo: number) =>
        `No se pueden quitar tantas piezas: hay solo ${saldo} en el stock. Revise la diferencia.`,
      motivoDoAjuste: 'Diga el motivo del ajuste. Eso ayuda a entender el cambio después.',
      motivoDeDesfazer: 'Diga el motivo para deshacer. Queda guardado en el historial.',
      minimo: 'Escriba el stock mínimo con un número entero. Puede ser cero.',
    },
  },
  planilha: {
    vazia: 'La planilla está vacía.',
    semLinhasDeProdutos: 'La planilla no tiene ninguna línea de productos.',
    semLinhasDePecas: 'La planilla no tiene ninguna línea de piezas.',
    colunas: {
      codigo: 'código',
      nome: 'nombre',
      categoria: 'categoría',
      custo: 'costo',
      preco: 'precio',
    },
    faltamColunas: (faltam: string) =>
      `Faltan columnas en la primera línea de la planilla: ${faltam}. Use los títulos código, nombre, categoría, costo y precio.`,
    linhasDemais: (max: number) =>
      `La planilla tiene demasiadas líneas. Envíe hasta ${max} productos por vez.`,
    faltaCodigo: 'Falta el código.',
    codigoLongo: 'El código es demasiado largo (hasta 60 letras o números).',
    codigoRepetido: (codigo: string, linha: number) =>
      `El código ${codigo} ya aparece en la línea ${linha}.`,
    faltaNome: 'Falta el nombre.',
    nomeLongo: 'El nombre es demasiado largo (hasta 200 letras).',
    tamTonLongo: 'El Talle/Tono es demasiado largo (hasta 30 letras).',
    faltaCategoria: 'Falta la categoría.',
    categoriaNaoExiste: (categoria: string) =>
      `La categoría "${categoria}" no existe. Registre la categoría antes.`,
    custoInvalido:
      'El costo no es un valor válido. Use números, con hasta dos decimales después de la coma.',
    precoInvalido:
      'El precio no es un valor válido. Use números, con hasta dos decimales después de la coma.',
    referenciaDaLinha: (linha: number, codigo: string) =>
      `Línea ${linha} (${codigo || 'sin código'})`,
    colunasDaCompra: {
      codigo: 'código',
      quantidade: 'cantidad',
      custo: 'costo',
    },
  },
  arquivo: {
    pagina: (n: number, total: number) => `Página ${n} de ${total}`,
    geradoEm: (quando: string, por: string) => `Generado el ${quando} por ${por}`,
    filtros: (lista: string) => `Filtros: ${lista}`,
    nenhum: 'ninguno',
    as: 'a las',
    ativo: 'Activo',
    inativo: 'Inactivo',
    sim: 'Sí',
    nao: 'No',
    situacao: 'Estado',
    dataDaCotacao: 'Fecha de la cotización',
    cotacao: 'Cotización',
    planilha: 'Informe',
    dona: 'Dueña',
    periodo: (de: string, ate: string) => `Período: ${de} a ${ate}`,
    comeco: 'el comienzo',
    hoje: 'hoy',
    nota: { nova: 'Nueva', reposicao: 'Reposición' },
  },
  painelArquivo: {
    totalEnviado: 'Total enviado',
    totalVendidoLiquido: 'Total vendido neto',
    totalLiquido: 'Total neto',
    cotacao: 'Dólar y Guaraní por la cotización del día de cada movimiento.',
    pecasEnviadas: 'Piezas enviadas',
    pecasDevolvidas: 'Piezas devueltas',
    pecasVendidas: 'Piezas vendidas',
    vendidoLiquido: 'Vendido neto',
  },
  vendedores: {
    telefoneDoTrabalho: 'Teléfono del trabajo',
    conjuge: {
      nome: 'Nombre del cónyuge',
      telefone: 'Teléfono del cónyuge',
      empresa: 'Empresa del cónyuge',
      renda: 'Ingreso del cónyuge',
      dataNascimento: 'Fecha de nacimiento del cónyuge',
    },
    nomeDaPessoa: 'Nombre de la persona',
  },
};
