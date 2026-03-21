$ErrorActionPreference = 'Stop'
$toolsDir = Split-Path -Parent $MyInvocation.MyCommand.Definition

$packageArgs = @{
  packageName    = 'ec2pf'
  url64bit       = 'https://github.com/KemalAbdic/ec2pf/releases/download/v{{VERSION}}/ec2pf-{{VERSION}}-windows-amd64.exe'
  checksum64     = '{{CHECKSUM}}'
  checksumType64 = 'sha256'
  fileFullPath   = Join-Path $toolsDir 'ec2pf.exe'
}

Get-ChocolateyWebFile @packageArgs
