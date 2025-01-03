FROM ubuntu:22.04
WORKDIR /app
COPY Copilot .
RUN chmod +x Copilot
EXPOSE 80
ENTRYPOINT ["./Copilot", "80"]
