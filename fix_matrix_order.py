#!/usr/bin/env python3

def main():
    with open('freq.csv', 'r') as fp:
        data = [x.strip().split(' ') for x in fp]
    with open('freq_fmo.csv', 'w') as fp:
        for row in zip(*data):
            fp.write(' '.join(row) + '\n')

if __name__ == '__main__':
    main()
