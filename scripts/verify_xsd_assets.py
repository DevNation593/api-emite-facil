#!/usr/bin/env python3
"""Inventory XML schema assets; intentionally fails if any imports cannot be resolved.
Usage: python scripts/verify_xsd_assets.py src/main/resources/xsd
El import de xmldsig-core-schema.xsd se busca en <directorio>/common/, la única copia compartida
(así lo resuelve ClasspathSchemaResolver); cualquier otro import, junto al XSD que lo declara.
"""
import hashlib
import pathlib
import sys
import xml.etree.ElementTree as ET

if len(sys.argv) != 2:
    print('Uso: python scripts/verify_xsd_assets.py <directorio-con-XSD>')
    sys.exit(2)
root = pathlib.Path(sys.argv[1])
if not root.is_dir():
    print(f'ERROR: no existe el directorio: {root}')
    sys.exit(2)
xsds = sorted(root.rglob('*.xsd'))
if not xsds:
    print(f'ERROR: no se encontraron archivos .xsd en {root}')
    sys.exit(2)
errors = []
for f in xsds:
    digest = hashlib.sha256(f.read_bytes()).hexdigest()
    print(f'XSD {f.relative_to(root)} sha256={digest}')
    try:
        x = ET.parse(f).getroot()
    except ET.ParseError as exc:
        errors.append(f'{f}: XML inválido: {exc}')
        continue
    for element in x.iter():
        if element.tag not in ('{http://www.w3.org/2001/XMLSchema}import', '{http://www.w3.org/2001/XMLSchema}include', '{http://www.w3.org/2001/XMLSchema}redefine'):
            continue
        loc = element.attrib.get('schemaLocation')
        if not loc:
            # Namespace-only xs:import must be resolved with a catalog/resolver; treat as outstanding.
            errors.append(f'{f}: import sin schemaLocation para namespace {element.attrib.get("namespace", "?")}')
            continue
        if '://' in loc or loc.startswith('/') or '..' in pathlib.PurePosixPath(loc).parts:
            errors.append(f'{f}: import no local seguro: {loc}')
            continue
        dependency = root / 'common' / loc if loc == 'xmldsig-core-schema.xsd' else f.parent / loc
        if not dependency.is_file():
            errors.append(f'{f}: falta dependencia {loc} (esperada en {dependency})')
        elif dependency.name == 'xmldsig-core-schema.xsd':
            try:
                dep_root = ET.parse(dependency).getroot()
                if dep_root.attrib.get('targetNamespace') != 'http://www.w3.org/2000/09/xmldsig#':
                    errors.append(f'{dependency}: targetNamespace XMLDSig incorrecto')
            except ET.ParseError as exc:
                errors.append(f'{dependency}: XML inválido: {exc}')
print(f'XSD detectados: {len(xsds)}')
if errors:
    for err in errors:
        print('ERROR:', err)
    sys.exit(1)
print('Imports locales presentes. La compilación real la cubre DocumentSchemasTest (./mvnw verify).')
