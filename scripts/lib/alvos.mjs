// Os dois aplicativos usam o MESMO mecanismo de instalador e de atualização (ADR-0009). Cada um tem a sua pasta,
// o seu nome de instalador e o seu canal no servidor (o do Corona Vendas fica em /atualizacoes/vendas/).
export const ALVOS = {
  gestao: {
    pasta: 'owner-app',
    nome: 'Corona Gestão',
    prefixo: 'Corona-Gestao',
    canal: '',
    npm: 'owner',
    // A configuração do electron-builder deste app já traz a publicação (latest.yml) com o endereço do ambiente.
    argsDoBuilder: () => [],
  },
  vendas: {
    pasta: 'seller-app',
    nome: 'Corona Vendas',
    prefixo: 'Corona-Vendas',
    canal: 'vendas',
    npm: 'vendas',
    // O electron-builder.yml do Corona Vendas não tem publicação (o CI só empacota, sem rede); aqui entra por argumento.
    argsDoBuilder: (urlDeAtualizacoes) => [
      '-c.publish.provider=generic',
      `-c.publish.url=${urlDeAtualizacoes}`,
      '-c.publish.channel=latest',
      '-c.win.signAndEditExecutable=true',
    ],
  },
};

/** Lê --app=gestao|vendas (padrão: gestao). */
export function alvoDoArgumento(argv) {
  const dado = argv.find((a) => a.startsWith('--app='))?.slice('--app='.length) ?? 'gestao';
  const alvo = ALVOS[dado];
  if (!alvo) {
    console.error(`Aplicativo desconhecido: "${dado}". Use --app=gestao ou --app=vendas.`);
    process.exit(1);
  }
  return alvo;
}
