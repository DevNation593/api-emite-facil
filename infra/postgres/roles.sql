-- Dos usuarios de base para producción: quien migra es dueño de las tablas y la aplicación solo lee
-- y escribe filas. Así la aplicación (o quien robe sus credenciales) no puede retirar los
-- disparadores que hacen inmutables los artefactos, ni borrar tablas, ni vaciarlas con TRUNCATE.
--
-- Se ejecuta una vez, como superusuario, ANTES de la primera migración, sobre la base ya creada:
--
--   EINVOICE_MIGRATOR_PASSWORD=... EINVOICE_APP_PASSWORD=... \
--     psql -v ON_ERROR_STOP=1 -v db=einvoice -f roles.sql
--
-- Las contraseñas se leen del entorno para que no queden en la línea de órdenes; también se
-- admiten como -v migrator_password=... -v app_password=...
--
-- Después, la aplicación arranca con:
--   SPRING_FLYWAY_USER=einvoice_migrator      SPRING_FLYWAY_PASSWORD=<migrator_password>
--   SPRING_DATASOURCE_USERNAME=einvoice_app   SPRING_DATASOURCE_PASSWORD=<app_password>
--
-- En desarrollo local no hace falta: un solo usuario migra y opera.

\if :{?migrator_password}
\else
\getenv migrator_password EINVOICE_MIGRATOR_PASSWORD
\endif
\if :{?app_password}
\else
\getenv app_password EINVOICE_APP_PASSWORD
\endif

SELECT format('CREATE ROLE einvoice_migrator LOGIN PASSWORD %L', :'migrator_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'einvoice_migrator') \gexec
SELECT format('CREATE ROLE einvoice_app LOGIN PASSWORD %L', :'app_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'einvoice_app') \gexec

REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO einvoice_migrator, einvoice_app;

\connect :"db"

-- El esquema es de quien migra: solo él crea, altera o borra objetos.
ALTER SCHEMA public OWNER TO einvoice_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO einvoice_app;

-- Todo lo que el migrador cree de aquí en adelante queda al alcance de la aplicación solo para
-- leer y escribir filas. TRUNCATE, REFERENCES y TRIGGER no se conceden.
ALTER DEFAULT PRIVILEGES FOR ROLE einvoice_migrator IN SCHEMA public
 GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO einvoice_app;
ALTER DEFAULT PRIVILEGES FOR ROLE einvoice_migrator IN SCHEMA public
 GRANT USAGE, SELECT ON SEQUENCES TO einvoice_app;
