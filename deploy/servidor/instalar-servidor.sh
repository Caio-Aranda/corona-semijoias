#!/usr/bin/env bash
# Prepara uma VPS Ubuntu nova para o Corona (ADR-0009, item 1). Rode UMA vez, como root, DEPOIS de conferir que
# a chave SSH do responsável já entra no servidor (/root/.ssh/authorized_keys). Pode rodar de novo sem estragar.
#
# O que faz: fuso UTC; atualizações de segurança automáticas; firewall (ufw) só com as portas 22 e 443;
# SSH só por chave (senha desligada); fail2ban; Docker; pastas do Corona; rclone.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "ERRO: rode como root (sudo -i)." >&2; exit 1; }
. /etc/os-release
[[ "${ID:-}" == "ubuntu" ]] || { echo "ERRO: este script é para Ubuntu (encontrei: ${ID:-desconhecido})." >&2; exit 1; }

# Trava de segurança: desligar a senha do SSH sem ter chave tranca o responsável do lado de fora.
if ! grep -qE '^(ssh-(rsa|ed25519)|ecdsa-sha2-|sk-)' /root/.ssh/authorized_keys 2>/dev/null; then
  echo "ERRO: não há chave SSH em /root/.ssh/authorized_keys. Instale a chave e teste o acesso antes; senão o SSH por senha seria desligado e você perderia o acesso." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive

echo "== 1/7 Pacotes e atualizações"
apt-get update -y
apt-get upgrade -y
apt-get install -y ufw fail2ban unattended-upgrades ca-certificates curl git openssl rclone docker.io docker-compose-v2 jq

echo "== 2/7 Fuso horário UTC"
timedatectl set-timezone UTC

echo "== 3/7 Atualizações de segurança automáticas (sem reiniciar sozinho)"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'CFG'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
CFG
cat > /etc/apt/apt.conf.d/52corona-unattended <<'CFG'
Unattended-Upgrade::Automatic-Reboot "false";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
CFG

echo "== 4/7 SSH só por chave"
cat > /etc/ssh/sshd_config.d/99-corona.conf <<'CFG'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
PubkeyAuthentication yes
MaxAuthTries 3
X11Forwarding no
CFG
sshd -t
systemctl reload ssh || systemctl reload sshd

echo "== 5/7 fail2ban (SSH)"
cat > /etc/fail2ban/jail.d/corona.local <<'CFG'
[sshd]
enabled = true
maxretry = 5
findtime = 10m
bantime = 1h
CFG
systemctl enable fail2ban
systemctl restart fail2ban

echo "== 6/7 Firewall: só 22 e 443"
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp comment 'SSH'
ufw allow 443/tcp comment 'Corona HTTPS'
ufw --force enable

echo "== 7/7 Pastas do Corona"
install -d -m 755 /opt/corona
install -d -m 750 /etc/corona
install -d -m 750 /etc/corona/tls
install -d -m 755 /srv/corona/atualizacoes
# A API roda como o usuário 10001 dentro do contêiner; esta pasta é dela (fotos e documentos).
install -d -m 750 -o 10001 -g 10001 /srv/corona/arquivos
install -d -m 750 /var/backups/corona
systemctl enable --now docker

echo
ufw status verbose | sed -n '1,12p'
timedatectl | grep -E 'Time zone'
cat <<'FIM'

Servidor preparado. Próximos passos (veja deploy/README.md):
  1. Copie ou clone o repositório em /opt/corona e entre em /opt/corona/deploy
  2. cp .env.example .env && chmod 600 .env   (e preencha)
  3. certificado/gerar-certificado.sh novo <IP-DA-VPS>
  4. docker compose up -d --build --wait
  5. servidor/verificar-portas.sh        (confere que só a 443 está aberta)
  6. monitoramento/instalar-timers.sh    (aviso de queda)
FIM
