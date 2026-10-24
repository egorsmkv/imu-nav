#!/usr/bin/env python3
"""Generate a deterministic public-data search corpus from a read-only Ukraine staging index."""
import argparse
from collections import Counter
from pathlib import Path
import random
import sqlite3


def build(database, output):
    rng = random.Random(20261024)
    cases = []
    kyiv = (50.4501, 30.5234)
    lviv = (49.8419, 24.0315)

    def add(category, query, near):
        query = ' '.join(query.split())
        item = (category, query, near)
        if item not in cases:
            cases.append(item)

    with sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True) as db:
        cities = db.execute("SELECT id,name,lat,lon FROM place WHERE kind IN ('city','town') ORDER BY population DESC,id LIMIT 24").fetchall()
        addresses = []
        for identifier, city, lat, lon in cities:
            rows = db.execute('''SELECT DISTINCT s.name,a.number FROM street s JOIN addr a ON a.street_id=s.id
                WHERE s.place_id=? ORDER BY (a.rowid*1103515245+12345)%2147483647,a.rowid LIMIT 100''', (identifier,)).fetchall()
            for street, number in rows[:5]:
                addresses.append((city, street, number, (lat, lon)))
        rng.shuffle(addresses)
        assert len(addresses) >= 100
        for i, (city, street, number, near) in enumerate(addresses[:24]):
            add('address_local', f'{city} {street} {number}', near)
            add('address_distant', f'{city} {street} {number}', lviv if near[1] > 30 else kyiv)
            if i < 12:
                add('address_no_location', f'{city} {street} {number}', None)
                add('street_first', f'{street} {city} {number}', near)
                add('missing_house', f'{city} {street} 9999', near)
                add('punctuation_case', f'  {city.upper()}, {street.upper()}, {number}  ', near)
        for city in cities[:20]:
            add('settlement', city[1], kyiv)
        prefixes = ['ш','ше','шев','шевч','го','гор','со','соб','це','цен','ми','мир','не','нез','пр','пе','по','ле','ко','ів']
        for i, query in enumerate(prefixes):
            add('prefix_cyrillic', query, [kyiv, lviv, None][i % 3])
        for i, query in enumerate(['k','ky','kyi','lv','lvi','od','ode','kh','kha','po','pol','iv']):
            add('prefix_latin', query, [kyiv, lviv, None][i % 3])
        common = db.execute('SELECT name FROM street GROUP BY name ORDER BY count(*) DESC,name LIMIT 16').fetchall()
        for i, (street,) in enumerate(common):
            add('street_common', street, [kyiv, lviv, None][i % 3])
        multi = db.execute('''SELECT p.name,s.name,a.number,p.lat,p.lon FROM place p
            JOIN street s ON s.place_id=p.id JOIN addr a ON a.street_id=s.id
            WHERE p.name LIKE '% %' AND p.kind IN ('city','town','village')
            GROUP BY p.id ORDER BY p.population DESC,p.id LIMIT 16''').fetchall()
        for city, street, number, lat, lon in multi:
            add('multiword_settlement', f'{city} {street} {number}', kyiv)
        parts = db.execute('''SELECT p.name,s.name,a.number,p.lat,p.lon FROM street s
            JOIN place p ON p.id=s.place_id JOIN addr a ON a.street_id=s.id
            WHERE a.number GLOB '[0-9]* к[0-9]*'
            GROUP BY p.id ORDER BY p.population DESC,p.id LIMIT 12''').fetchall()
        for city, street, number, lat, lon in parts:
            add('building_part', f'{city} {street} {number}', (lat, lon))
        variants = db.execute('''SELECT p.name,s.name,a.number,p.lat,p.lon FROM street s
            JOIN place p ON p.id=s.place_id JOIN addr a ON a.street_id=s.id
            WHERE a.number LIKE '%/%' OR a.number GLOB '[0-9]*[а-яa-z]'
            GROUP BY p.id ORDER BY p.population DESC,p.id LIMIT 12''').fetchall()
        for city, street, number, lat, lon in variants:
            add('slash_letter', f'{city} {street} {number}', (lat, lon))
        for i, query in enumerate(['Киїфф','Львівв','Хрещщатик','Шевченккко','zzzzmissing','неіснуючавулиця',
                                    'Київ zzzz 22','Львів qqqq 1','Біла Церква zzzz 9','!!!','22','к1']):
            add('typo_absent', query, [kyiv, lviv, None][i % 3])
    with output.open('x') as stream:
        stream.write('# category\tpublic query\tlatitude\tlongitude; - means no reference\n')
        for category, query, near in cases:
            coords = ('-', '-') if near is None else (str(near[0]), str(near[1]))
            stream.write('\t'.join((category, query, *coords)) + '\n')
    print('cases', len(cases), dict(Counter(case[0] for case in cases)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('database', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    build(args.database, args.output)
