FROM node:20-alpine
WORKDIR /app
COPY schema.sql server.js server.test.js ./
EXPOSE 8088
ENV PORT=8088
CMD ["node", "server.js"]
