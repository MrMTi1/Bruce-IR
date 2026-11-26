import os
import json
import gzip

def parse_ir_file(file_path):
    commands = []
    name = ""
    freq = 38000
    pattern = []
    protocol = None
    address = None
    command = None
    file_basename = os.path.splitext(os.path.basename(file_path))[0].replace('_', ' ')

    def add_current():
        nonlocal name, freq, pattern, protocol, address, command
        if name:
            if protocol and (address or command):
                commands.append({
                    "name": name,
                    "frequency": freq,
                    "protocol": protocol,
                    "address": address or "0x00",
                    "command": command or "0x00",
                    "type": "cmd"
                })
            else:
                commands.append({
                    "name": name,
                    "frequency": freq,
                    "pattern": pattern if pattern else [100, 100],
                    "type": "cmd"
                })
        name = ""
        freq = 38000
        pattern = []
        protocol = None
        address = None
        command = None

    try:
        with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
            for line in f:
                t = line.strip()
                if t.startswith("#"):
                    add_current()
                    continue
                if t.startswith("name:"):
                    add_current()
                    name = t.split(":", 1)[1].strip()
                elif t.startswith("frequency:"):
                    try:
                        freq = int(t.split(":", 1)[1].strip())
                    except ValueError:
                        freq = 38000
                elif t.startswith("protocol:"):
                    protocol = t.split(":", 1)[1].strip()
                elif t.startswith("address:"):
                    address = t.split(":", 1)[1].strip().replace(" ", "")
                elif t.startswith("command:"):
                    command = t.split(":", 1)[1].strip().replace(" ", "")
                elif t.startswith("data:"):
                    raw_str = t.split(":", 1)[1].strip()
                    parts = raw_str.split()
                    raw_ints = []
                    for p in parts:
                        try:
                            raw_ints.append(int(float(p)))
                        except ValueError:
                            pass
                    pattern = raw_ints[1:] if (raw_ints and raw_ints[0] == 0) else raw_ints
        add_current()
    except Exception:
        pass

    if not commands:
        commands.append({
            "name": file_basename,
            "frequency": 38000,
            "pattern": [100, 100],
            "type": "cmd"
        })

    return commands

def build_tree(dir_path):
    folder_name = os.path.basename(dir_path) or "FLIPPER-IRDB-MAIN"
    if folder_name.startswith('.'):
        return None

    items = []
    try:
        entries = sorted(os.listdir(dir_path))
    except Exception:
        return None

    for entry in entries:
        if entry.startswith('.'):
            continue
        full_path = os.path.join(dir_path, entry)
        if os.path.isdir(full_path):
            sub_folder = build_tree(full_path)
            if sub_folder and sub_folder.get("items"):
                items.append(sub_folder)
        elif entry.endswith('.ir'):
            cmds = parse_ir_file(full_path)
            if cmds:
                remote_name = os.path.splitext(entry)[0].replace('_', ' ')
                items.append({
                    "name": remote_name,
                    "type": "folder",
                    "frequency": 0,
                    "pattern": [],
                    "items": cmds
                })

    if not items:
        return None

    return {
        "name": folder_name,
        "type": "folder",
        "frequency": 0,
        "pattern": [],
        "items": items
    }

if __name__ == "__main__":
    src = "app/src/main/assets/Flipper-IRDB-main/Flipper-IRDB-main"
    out = "app/src/main/assets/flipper_db.json.gz"
    print("Generuję flipper_db.json.gz...")
    tree = build_tree(src)
    if tree:
        with gzip.open(out, 'wt', encoding='utf-8') as f:
            json.dump(tree, f, ensure_ascii=False, separators=(',', ':'))
        print("Gotowe! Rozmiar:", os.path.getsize(out))
