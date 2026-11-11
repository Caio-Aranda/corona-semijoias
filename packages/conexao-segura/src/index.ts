export {
  ENDERECO_PADRAO_DA_API,
  ErroDeConfiguracao,
  enderecoDaApi,
  exigirImpressoesFora,
  impressoesDoServidor,
  validarConfiguracaoDeProducao,
  versaoDoApp,
  type ConfiguracaoDeProducao,
} from './configuracao';
export {
  decidirCertificado,
  impressaoDaChave,
  instalarCertificadoFixado,
  VEREDITO,
  type ServidorFixado,
  type SessaoComVerificador,
} from './conexao-fixada';
export {
  PARTICAO_DO_ATUALIZADOR,
  ServicoDeAtualizacao,
  type AtualizadorDoElectron,
  type AvisoDeAtualizacao,
  type Relogio,
} from './atualizacao';
