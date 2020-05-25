import argparse

from casperlabs_client import CasperLabsClient
from casperlabs_client.arg_types import natural
from casperlabs_client.decorators import guarded_command


NAME: str = "vdag"
HELP: str = "DAG in DOT format. You need to install Graphviz from https://www.graphviz.org/ to use it."
DOT_FORMATS = (
    "canon,cmap,cmapx,cmapx_np,dot,dot_json,eps,fig,gd,gd2,gif,gv,imap,imap_np,ismap,jpe,jpeg,jpg,json,"
    "json0,mp,pdf,pic,plain,plain-ext,png,pov,ps,ps2,svg,svgz,tk,vml,vmlz,vrml,wbmp,x11,xdot,xdot1.2,"
    "xdot1.4,xdot_json,xlib"
)


def dot_output(file_name):
    """
    Check file name has an extension of one of file formats supported by Graphviz.
    """
    parts = file_name.split(".")
    if len(parts) == 1:
        raise argparse.ArgumentTypeError(
            f"'{file_name}' has no extension indicating file format"
        )
    else:
        file_format = parts[-1]
        if file_format not in DOT_FORMATS.split(","):
            raise argparse.ArgumentTypeError(
                f"File extension {file_format} not recognized, must be one of {DOT_FORMATS}"
            )
    return file_name


OPTIONS = [
    [
        ("-d", "--depth"),
        dict(required=True, type=natural, help="depth in terms of block height"),
    ],
    [
        ("-o", "--out"),
        dict(
            required=False,
            type=dot_output,
            help=f"output image filename, outputs to stdout if not specified, must end with one of {DOT_FORMATS}",
        ),
    ],
    [
        ("-s", "--show-justification-lines"),
        dict(action="store_true", help="if justification lines should be shown"),
    ],
    [
        ("--stream",),
        dict(
            required=False,
            choices=("single-output", "multiple-outputs"),
            help="subscribe to changes, '--out' has to be specified, valid values are 'single-output', 'multiple-outputs'",
        ),
    ],
]


@guarded_command
def method(casperlabs_client: CasperLabsClient, args):
    for o in casperlabs_client.visualize_dag(
        args.depth, args.out, args.show_justification_lines, args.stream
    ):
        if not args.out:
            print(o)
            break
