#!/bin/sh
# 把发布用的 keyboards.html（无 doctype/head/body）包成本地可直接打开的 index.html
cd "$(dirname "$0")/.."
{
  printf '<!doctype html>\n<html lang="zh-CN">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n<style>[hidden]{display:none!important}body{margin:0}:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}</style>\n'
  sed -n '1,/^<div class="page">/p' keyboards.html | sed '$d'
  printf '</head>\n<body>\n'
  sed -n '/^<div class="page">/,$p' keyboards.html
  printf '</body>\n</html>\n'
} > index.html
