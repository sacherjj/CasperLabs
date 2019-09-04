import os

from test.cl_node.common import testing_root_path

# This file generates the code for
# test.cl_node.common.py :: Contract class


def get_wasm_files() -> list:
    wasm_folder = testing_root_path() / "resources"
    files = []
    for file in os.listdir(wasm_folder):
        if file.endswith(".wasm"):
            files.append(file)
    return files


def field_from_filename(filename: str) -> str:
    if filename.startswith("test_"):
        return filename[5:-5]
    return filename[:-5]


if __name__ == "__main__":
    fields_filename = [(field_from_filename(fn).upper(), fn) for fn in get_wasm_files()]

    fields_filename.sort(key=lambda tup: tup[0])
    print("class Contract:")
    for field, filename in fields_filename:
        print(f'    {field} = "{filename}"')
