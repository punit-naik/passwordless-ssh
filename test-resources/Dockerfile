FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=UTC
ENV USER_NAME=cluster
ENV USER_PASSWORD=cluster

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates \
       iproute2 \
       openssh-server \
       procps \
       sudo \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /var/run/sshd /etc/sudoers.d \
    && sed -ri 's/^#?PasswordAuthentication .*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
    && sed -ri 's/^#?PermitRootLogin .*/PermitRootLogin no/' /etc/ssh/sshd_config \
    && printf 'UsePAM no\n' >> /etc/ssh/sshd_config

EXPOSE 22

CMD ["/bin/bash", "-lc", "set -euo pipefail; if ! id -u \"$USER_NAME\" >/dev/null 2>&1; then useradd -m -s /bin/bash \"$USER_NAME\"; fi; echo \"$USER_NAME:$USER_PASSWORD\" | chpasswd; usermod -aG sudo \"$USER_NAME\"; printf '%s ALL=(ALL) NOPASSWD:ALL\\n' \"$USER_NAME\" > /etc/sudoers.d/90-cluster-user; chmod 0440 /etc/sudoers.d/90-cluster-user; mkdir -p /home/$USER_NAME/.ssh; chown -R $USER_NAME:$USER_NAME /home/$USER_NAME/.ssh; ssh-keygen -A; exec /usr/sbin/sshd -D -e"]
