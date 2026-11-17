import { textosVivos } from './idioma/idioma';
import { ES_TEXTOS_DE_RELATORIOS_DE_CONTROLE } from './idioma/es/relatorios-controle';
/**
 * Textos dos relatórios de Controle da dona (tarefa 8.9), do glossário da interface ("Relatórios de controle"). A dona é tratada
 * por "você". Na tela nunca aparecem os nomes técnicos de ação, de situação ou de resultado: o servidor já manda o texto da loja.
 */
export const PT_TEXTOS_DE_RELATORIOS_DE_CONTROLE = {
  grupoDoMenu: 'Controle',
  auditoria: {
    titulo: 'Auditoria: quem fez o quê',
    explicacao:
      'Cada mudança importante feita no sistema (preço, estoque, desconto, cancelamento, reembolso, autorização, restrição e outras): quem fez, quando, o que foi e como era antes e depois.',
    nota: 'Senhas nunca aparecem aqui. Quando não há uma pessoa, a mudança foi feita pelo próprio sistema.',
  },
  computadores: {
    titulo: 'Computadores e envios',
    explicacao:
      'Os computadores de cada vendedor: quando receberam dados da loja pela última vez, quando enviaram as vendas, quantas vendas esperam para ser enviadas e quantos envios precisam da sua decisão.',
    nota: 'Para decidir um envio, abra Computadores no menu.',
    situacoes: {
      TODAS: 'Todos os computadores',
      ATIVO: 'Em uso',
      AGUARDANDO_LIBERACAO: 'Esperando a sua liberação',
      SUBSTITUIDO: 'Trocados por outro',
      BLOQUEADO: 'Bloqueados',
    },
  },
  acessos: {
    titulo: 'Acessos: entradas e erros',
    explicacao:
      'Cada vez que alguém tentou entrar: quem, em qual programa, se deu certo, o endereço de internet e se o acesso ficou bloqueado por muitos erros.',
    nota: 'Se o nome da pessoa estiver vazio, o usuário digitado não existe.',
    semPessoa: 'Usuário que não existe',
    resultados: {
      SUCESSO: 'Entrou',
      SENHA_INCORRETA: 'Senha errada',
      BLOQUEADO: 'Recusada: acesso bloqueado',
      USUARIO_DESATIVADO: 'Usuário desativado',
      APP_INCOMPATIVEL: 'Programa muito antigo',
      SENHA_TEMPORARIA_VENCIDA: 'Senha temporária vencida',
      PEDIDO_INVALIDO: 'Computador que não combina com o acesso',
      BLOQUEIO_LIMPO: 'Bloqueio retirado ao redefinir a senha',
    } as Record<string, string>,
  },
} as const;

export const TEXTOS_DE_RELATORIOS_DE_CONTROLE = textosVivos(
  'rel-controle',
  PT_TEXTOS_DE_RELATORIOS_DE_CONTROLE,
  ES_TEXTOS_DE_RELATORIOS_DE_CONTROLE,
);
