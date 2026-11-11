// O endereço do servidor, a versão e o certificado fixado são comuns aos dois aplicativos e moram num pacote
// neutro (ADR-0003). Este arquivo só repassa, para o restante do aplicativo seguir importando daqui.
export {
  ENDERECO_PADRAO_DA_API,
  ErroDeConfiguracao,
  enderecoDaApi,
  exigirImpressoesFora,
  impressoesDoServidor,
  validarConfiguracaoDeProducao,
  versaoDoApp,
  type ConfiguracaoDeProducao,
} from '@corona/conexao-segura';
